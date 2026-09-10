package com.ai.konwledgerepo.service.knowledge;

import com.ai.konwledgerepo.common.BizException;
import com.ai.konwledgerepo.common.JsonLists;
import com.ai.konwledgerepo.common.PromptCatalog;
import com.ai.konwledgerepo.common.Texts;
import com.ai.konwledgerepo.dto.QaPairRequest;
import com.ai.konwledgerepo.dto.QaPairResponse;
import com.ai.konwledgerepo.entity.QaPair;
import com.ai.konwledgerepo.entity.ReviewStatus;
import com.ai.konwledgerepo.entity.SourceType;
import com.ai.konwledgerepo.model.ModelFactory;
import com.ai.konwledgerepo.repository.DocumentRepository;
import com.ai.konwledgerepo.repository.QaPairRepository;
import com.ai.konwledgerepo.service.knowledgebase.KnowledgeBaseService;
import com.ai.konwledgerepo.service.vector.SourceIndexer;
import com.ai.konwledgerepo.service.workspace.WorkspaceAccess;
import com.ai.konwledgerepo.tracing.LlmTrace;
import com.ai.konwledgerepo.tracing.QaTracing;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 问答对业务：CRUD、审核、启用/禁用、AI 归一化与同义扩展、版本历史与回退。
 */
@Service
public class QaPairService {

    private static final Logger log = LoggerFactory.getLogger(QaPairService.class);
    private static final Pattern JSON_OBJECT_PATTERN = Pattern.compile("\\{.*}", Pattern.DOTALL);

    private final QaPairRepository repository;
    private final KnowledgeBaseService kbService;
    private final DocumentRepository documentRepository;
    private final ModelFactory modelFactory;
    private final SourceIndexer sourceIndexer;
    private final WorkspaceAccess workspaceAccess;
    private final PromptCatalog promptCatalog;
    private final ObjectMapper objectMapper;
    private final QaTracing qaTracing;
    private final TransactionOperations transactionOperations;

    public QaPairService(QaPairRepository repository,
                         KnowledgeBaseService kbService,
                         DocumentRepository documentRepository,
                         ModelFactory modelFactory,
                         SourceIndexer sourceIndexer,
                         WorkspaceAccess workspaceAccess,
                         PromptCatalog promptCatalog,
                         ObjectMapper objectMapper,
                         QaTracing qaTracing,
                         TransactionOperations transactionOperations) {
        this.repository = repository;
        this.kbService = kbService;
        this.documentRepository = documentRepository;
        this.modelFactory = modelFactory;
        this.sourceIndexer = sourceIndexer;
        this.workspaceAccess = workspaceAccess;
        this.promptCatalog = promptCatalog;
        this.objectMapper = objectMapper;
        this.qaTracing = qaTracing;
        this.transactionOperations = transactionOperations;
    }

    public List<QaPairResponse> list(Long kbId, String status) {
        List<QaPair> list = (status == null || status.isBlank())
                ? repository.findByKbIdAndDeletedFalseOrderByIdDesc(kbId)
                : repository.findByKbIdAndDeletedFalseAndStatusOrderByIdDesc(kbId, status);
        return list.stream().map(this::toResponse).toList();
    }

    @Transactional
    public QaPairResponse create(Long kbId, QaPairRequest request) {
        return doCreate(kbId, request);
    }

    /**
     * 抽取任务专用创建（去重）：同知识库相同问题的未删 DRAFT 草稿先软删，
     * 用最新抽取结果替换，避免重复草稿堆积；已审核（APPROVED/REJECTED/DISABLED）记录不受影响。
     * <p>
     * 并发安全：最多 2 次尝试——事务内软删同名 DRAFT 后插入新行；
     * DuplicateKeyException 触发重试一次。
     */
    public QaPairResponse createDraft(Long kbId, QaPairRequest request) {
        kbService.getEntity(kbId);
        String question = request.question() == null ? null : request.question().trim();
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                return transactionOperations.execute(status -> {
                    if (question != null && !question.isEmpty()) {
                        List<QaPair> dups = repository.findByKbIdAndQuestionAndDeletedFalseAndStatus(
                                kbId, question, ReviewStatus.DRAFT.value());
                        for (QaPair dup : dups) {
                            dup.setDeleted(true);
                            repository.save(dup);
                        }
                        repository.flush(); // 软删先落库，释放 active_question 唯一约束位
                    }
                    return doCreate(kbId, request);
                });
            } catch (DuplicateKeyException e) {
                log.warn("createDraft 并发冲突（kb={}, question={}），重试一次", kbId, question);
                if (attempt >= 1) {
                    throw new BizException("草稿创建冲突，请重试");
                }
            }
        }
        throw new BizException("草稿创建失败"); // unreachable
    }

    /** 新建记录（默认 DRAFT + 版本 1）；create / createDraft 共用，事务由调用方注解承载 */
    private QaPairResponse doCreate(Long kbId, QaPairRequest request) {
        kbService.getEntity(kbId);
        QaPair pair = new QaPair();
        apply(pair, request);
        pair.setKbId(kbId);
        pair.setHistoryGroupId(UUID.randomUUID().toString());
        pair.setVersion(1);
        repository.saveAndFlush(pair);
        return toResponse(pair);
    }

    @Transactional
    public QaPairResponse update(Long id, QaPairRequest request, Long workspaceId) {
        QaPair current = requireInWorkspace(id, workspaceId);
        current.setDeleted(true);
        repository.save(current);
        repository.flush(); // 关键：软删先落库，释放 active_question 唯一约束位
        syncIndex(current);

        QaPair fresh = new QaPair();
        apply(fresh, request);
        fresh.setKbId(current.getKbId());
        fresh.setHistoryGroupId(current.getHistoryGroupId());
        fresh.setVersion(current.getVersion() + 1);
        fresh.setStatus(ReviewStatus.DRAFT.value());
        repository.save(fresh);
        syncIndex(fresh);
        return toResponse(fresh);
    }

    @Transactional
    public void delete(Long id, Long workspaceId) {
        QaPair current = requireInWorkspace(id, workspaceId);
        current.setDeleted(true);
        repository.save(current);
        repository.flush(); // 关键：软删先落库，释放 active_question 唯一约束位
        syncIndex(current);
    }

    @Transactional
    public QaPairResponse approve(Long id, Long workspaceId) {
        QaPair pair = requireInWorkspace(id, workspaceId);
        pair.setStatus(ReviewStatus.APPROVED.value());
        repository.save(pair);
        syncIndex(pair);
        return toResponse(pair);
    }

    @Transactional
    public QaPairResponse reject(Long id, Long workspaceId) {
        QaPair pair = requireInWorkspace(id, workspaceId);
        pair.setStatus(ReviewStatus.REJECTED.value());
        repository.save(pair);
        syncIndex(pair);
        return toResponse(pair);
    }

    @Transactional
    public QaPairResponse disable(Long id, Long workspaceId) {
        QaPair pair = requireInWorkspace(id, workspaceId);
        pair.setStatus(ReviewStatus.DISABLED.value());
        repository.save(pair);
        syncIndex(pair);
        return toResponse(pair);
    }

    @Transactional
    public QaPairResponse enable(Long id, Long workspaceId) {
        QaPair pair = requireInWorkspace(id, workspaceId);
        pair.setStatus(ReviewStatus.APPROVED.value());
        repository.save(pair);
        syncIndex(pair);
        return toResponse(pair);
    }

    /** AI 问题归一化 + 同义问法扩展（AI 加工类任务，使用抽取用途模型，限当前工作空间）。
     *  不挂方法级事务：LLM 调用不应占用数据库事务/连接，落库为单条 save。 */
    public QaPairResponse normalize(Long id, Long workspaceId) {
        QaPair pair = requireInWorkspace(id, workspaceId);
        ChatModel chat = modelFactory.getChatModelByUsage("EXTRACT", workspaceId);
        String prompt = promptCatalog.render("normalize-qa", Map.of("question", pair.getQuestion()));
        String response = LlmTrace.call(qaTracing, chat, prompt);

        NormalizeResult result = parseNormalize(response);
        pair.setNormalizedQuestion(result.normalized());
        pair.setSynonyms(toJson(result.synonyms()));
        repository.save(pair);
        // 归一化/同义扩展改变了可检索内容，若已审核通过则重新索引
        syncIndex(pair);
        return toResponse(pair);
    }

    public List<QaPairResponse> versions(Long id, Long workspaceId) {
        // 容忍软删：任意版本 id 均可查该版本组全部历史
        QaPair any = repository.findById(id)
                .orElseThrow(() -> new BizException("记录不存在"));
        workspaceAccess.requireKb(any.getKbId(), workspaceId);
        return repository.findByHistoryGroupIdOrderByVersionDesc(any.getHistoryGroupId()).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public QaPairResponse rollback(Long id, int version, Long workspaceId) {
        QaPair current = requireInWorkspace(id, workspaceId);
        QaPair target = repository.findByHistoryGroupIdOrderByVersionDesc(current.getHistoryGroupId()).stream()
                .filter(v -> v.getVersion() == version)
                .findFirst()
                .orElseThrow(() -> new BizException("版本不存在"));
        if (target.getId().equals(current.getId())) {
            throw new BizException("当前已是最新版本");
        }
        current.setDeleted(true);
        repository.save(current);
        repository.flush(); // 关键：软删先落库，释放 active_question 唯一约束位
        syncIndex(current);
        target.setDeleted(false);
        target.setStatus(ReviewStatus.APPROVED.value());
        repository.save(target);
        syncIndex(target);
        return toResponse(target);
    }

    private QaPair getActive(Long id) {
        return repository.findById(id)
                .filter(p -> !Boolean.TRUE.equals(p.getDeleted()))
                .orElseThrow(() -> new BizException("记录不存在"));
    }

    /** 校验记录存在且其知识库属于当前工作空间（防跨空间按 id 越权） */
    private QaPair requireInWorkspace(Long id, Long workspaceId) {
        QaPair pair = getActive(id);
        workspaceAccess.requireKb(pair.getKbId(), workspaceId);
        return pair;
    }

    /**
     * 按记录 id 取所属知识库 id（含空间归属校验），供 Controller 层做知识库级 ACL 判定。
     * 本身不做 ACL——ACL 由调用方统一走 {@link WorkspaceAccess#requireKbAccess}，
     * 保证写路径的 kb 权限判定口径唯一。
     */
    public Long kbIdOf(Long id, Long workspaceId) {
        return requireInWorkspace(id, workspaceId).getKbId();
    }

    /** 按版本记录 id 取所属知识库 id（容忍软删，供 versions 等只读接口做 ACL 判定） */
    public Long kbIdOfAnyVersion(Long id, Long workspaceId) {
        QaPair any = repository.findById(id)
                .orElseThrow(() -> new BizException("记录不存在"));
        workspaceAccess.requireKb(any.getKbId(), workspaceId);
        return any.getKbId();
    }

    /**
     * ES 索引同步（多源召回）：审核通过（APPROVED）写入索引，软删/拒绝/禁用等移出索引。
     * 实现见 {@link SourceIndexer}。
     */
    private void syncIndex(QaPair pair) {
        sourceIndexer.sync(SourceType.QA.value(), pair.getId(), pair.getKbId(), pair.getSourceDocId(),
                pair.getSourceDocName(), pair.getQuestion(), indexContent(pair),
                Boolean.TRUE.equals(pair.getDeleted()), pair.getStatus());
    }

    /** 构建问答对索引内容：问题（含标准问法/同义问法）+ 答案 */
    private String indexContent(QaPair pair) {
        StringBuilder sb = new StringBuilder("问题：").append(pair.getQuestion());
        if (!Texts.isBlank(pair.getNormalizedQuestion())) {
            sb.append("\n标准问法：").append(pair.getNormalizedQuestion());
        }
        List<String> synonyms = parseList(pair.getSynonyms());
        if (!synonyms.isEmpty()) {
            sb.append("\n同义问法：").append(String.join("、", synonyms));
        }
        sb.append("\n答案：").append(pair.getAnswer());
        return sb.toString();
    }

    private void apply(QaPair pair, QaPairRequest request) {
        pair.setQuestion(request.question());
        pair.setAnswer(request.answer());
        if (request.sourceDocId() != null) {
            documentRepository.findById(request.sourceDocId()).ifPresent(d -> {
                pair.setSourceDocId(d.getId());
                pair.setSourceDocName(d.getFileName());
            });
        }
    }

    public QaPairResponse toResponse(QaPair pair) {
        return new QaPairResponse(
                pair.getId(), pair.getKbId(), pair.getQuestion(), pair.getNormalizedQuestion(),
                parseList(pair.getSynonyms()), pair.getAnswer(), pair.getSourceDocId(), pair.getSourceDocName(),
                pair.getStatus(), pair.getVersion(), Boolean.TRUE.equals(pair.getDeleted()), pair.getCreatedAt());
    }

    private NormalizeResult parseNormalize(String response) {
        String normalized = null;
        List<String> synonyms = new ArrayList<>();
        try {
            Matcher matcher = JSON_OBJECT_PATTERN.matcher(response == null ? "" : response);
            if (matcher.find()) {
                JsonNode node = objectMapper.readTree(matcher.group());
                normalized = node.path("normalized").asText(null);
                JsonNode syn = node.path("synonyms");
                if (syn.isArray()) {
                    syn.forEach(s -> synonyms.add(s.asText()));
                }
            }
        } catch (Exception ignored) {
            // 解析失败，保留当前值
        }
        return new NormalizeResult(normalized, synonyms);
    }

    private List<String> parseList(String json) {
        return JsonLists.readStrings(json, objectMapper);
    }

    private String toJson(List<String> list) {
        return JsonLists.writeOrNull(list, objectMapper);
    }

    private record NormalizeResult(String normalized, List<String> synonyms) {
    }
}
