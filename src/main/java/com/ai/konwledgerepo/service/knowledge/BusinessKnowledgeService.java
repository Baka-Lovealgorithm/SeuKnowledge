package com.ai.konwledgerepo.service.knowledge;

import com.ai.konwledgerepo.common.BizException;
import com.ai.konwledgerepo.common.Texts;
import com.ai.konwledgerepo.dto.BusinessKnowledgeRequest;
import com.ai.konwledgerepo.dto.BusinessKnowledgeResponse;
import com.ai.konwledgerepo.entity.BusinessKnowledge;
import com.ai.konwledgerepo.entity.Document;
import com.ai.konwledgerepo.entity.ReviewStatus;
import com.ai.konwledgerepo.entity.SourceType;
import com.ai.konwledgerepo.repository.BusinessKnowledgeRepository;
import com.ai.konwledgerepo.repository.DocumentRepository;
import com.ai.konwledgerepo.service.knowledgebase.KnowledgeBaseService;
import com.ai.konwledgerepo.service.vector.SourceIndexer;
import com.ai.konwledgerepo.service.workspace.WorkspaceAccess;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 业务知识业务：CRUD、审核、合并、版本历史与回退。
 *
 * 版本约定：同 historyGroupId 共享版本组；编辑 = 软删当前 + 新建高版本；回退 = 软删当前 + 激活目标版本。
 */
@Service
public class BusinessKnowledgeService {

    private final BusinessKnowledgeRepository repository;
    private final KnowledgeBaseService kbService;
    private final DocumentRepository documentRepository;
    private final SourceIndexer sourceIndexer;
    private final WorkspaceAccess workspaceAccess;
    private final ObjectMapper objectMapper;

    public BusinessKnowledgeService(BusinessKnowledgeRepository repository,
                                    KnowledgeBaseService kbService,
                                    DocumentRepository documentRepository,
                                    SourceIndexer sourceIndexer,
                                    WorkspaceAccess workspaceAccess,
                                    ObjectMapper objectMapper) {
        this.repository = repository;
        this.kbService = kbService;
        this.documentRepository = documentRepository;
        this.sourceIndexer = sourceIndexer;
        this.workspaceAccess = workspaceAccess;
        this.objectMapper = objectMapper;
    }

    public List<BusinessKnowledgeResponse> list(Long kbId, String status) {
        List<BusinessKnowledge> list = (status == null || status.isBlank())
                ? repository.findByKbIdAndDeletedFalseOrderByIdDesc(kbId)
                : repository.findByKbIdAndDeletedFalseAndStatusOrderByIdDesc(kbId, status);
        return list.stream().map(this::toResponse).toList();
    }

    @Transactional
    public BusinessKnowledgeResponse create(Long kbId, BusinessKnowledgeRequest request) {
        return doCreate(kbId, request);
    }

    /**
     * 抽取任务专用创建（去重）：同知识库相同术语的未删 DRAFT 草稿先软删，
     * 用最新抽取结果替换，避免重复草稿堆积；已审核（APPROVED/REJECTED）记录不受影响。
     * 与普通 create 共用 doCreate（同事务由本方法注解承载，避免自调用绕过代理）。
     */
    @Transactional
    public BusinessKnowledgeResponse createDraft(Long kbId, BusinessKnowledgeRequest request) {
        kbService.getEntity(kbId);
        String term = request.term() == null ? null : request.term().trim();
        if (term != null && !term.isEmpty()) {
            List<BusinessKnowledge> dups = repository.findByKbIdAndTermAndDeletedFalseAndStatus(kbId, term, ReviewStatus.DRAFT.value());
            for (BusinessKnowledge dup : dups) {
                dup.setDeleted(true);
                repository.save(dup);
            }
        }
        return doCreate(kbId, request);
    }

    /** 新建记录（默认 DRAFT + 版本 1）；create / createDraft 共用，事务由调用方注解承载 */
    private BusinessKnowledgeResponse doCreate(Long kbId, BusinessKnowledgeRequest request) {
        kbService.getEntity(kbId);
        BusinessKnowledge bk = new BusinessKnowledge();
        apply(bk, request);
        bk.setKbId(kbId);
        bk.setHistoryGroupId(UUID.randomUUID().toString());
        bk.setVersion(1);
        repository.save(bk);
        return toResponse(bk);
    }

    @Transactional
    public BusinessKnowledgeResponse update(Long id, BusinessKnowledgeRequest request, Long workspaceId) {
        BusinessKnowledge current = requireInWorkspace(id, workspaceId);
        current.setDeleted(true);
        repository.save(current);
        syncIndex(current);

        BusinessKnowledge fresh = new BusinessKnowledge();
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
        BusinessKnowledge current = requireInWorkspace(id, workspaceId);
        current.setDeleted(true);
        repository.save(current);
        syncIndex(current);
    }

    @Transactional
    public BusinessKnowledgeResponse approve(Long id, Long workspaceId) {
        BusinessKnowledge bk = requireInWorkspace(id, workspaceId);
        bk.setStatus(ReviewStatus.APPROVED.value());
        repository.save(bk);
        syncIndex(bk);
        return toResponse(bk);
    }

    @Transactional
    public BusinessKnowledgeResponse reject(Long id, Long workspaceId) {
        BusinessKnowledge bk = requireInWorkspace(id, workspaceId);
        bk.setStatus(ReviewStatus.REJECTED.value());
        repository.save(bk);
        syncIndex(bk);
        return toResponse(bk);
    }

    /**
     * 合并：将来源记录并入 targetId（别名并集去重、缺失字段补全），来源软删。
     */
    @Transactional
    public BusinessKnowledgeResponse merge(Long sourceId, Long targetId, Long workspaceId) {
        BusinessKnowledge source = requireInWorkspace(sourceId, workspaceId);
        BusinessKnowledge target = requireInWorkspace(targetId, workspaceId);
        if (source.getHistoryGroupId().equals(target.getHistoryGroupId())) {
            throw new BizException("不能合并同版本组内的两条记录");
        }

        Set<String> aliases = new LinkedHashSet<>(parseAliases(target.getAliases()));
        aliases.addAll(parseAliases(source.getAliases()));
        target.setAliases(toJson(aliases));

        if (Texts.isBlank(target.getDefinition()) && !Texts.isBlank(source.getDefinition())) target.setDefinition(source.getDefinition());
        if (Texts.isBlank(target.getScope()) && !Texts.isBlank(source.getScope())) target.setScope(source.getScope());
        if (Texts.isBlank(target.getExample()) && !Texts.isBlank(source.getExample())) target.setExample(source.getExample());
        if (Texts.isBlank(target.getProhibitedRules()) && !Texts.isBlank(source.getProhibitedRules())) target.setProhibitedRules(source.getProhibitedRules());
        if (target.getSourceDocId() == null && source.getSourceDocId() != null) {
            target.setSourceDocId(source.getSourceDocId());
            target.setSourceDocName(source.getSourceDocName());
        }

        source.setDeleted(true);
        repository.save(source);
        repository.save(target);
        syncIndex(source);
        syncIndex(target);
        return toResponse(target);
    }

    public List<BusinessKnowledgeResponse> versions(Long id, Long workspaceId) {
        // 容忍软删：任意版本 id 均可查该版本组全部历史
        BusinessKnowledge any = repository.findById(id)
                .orElseThrow(() -> new BizException("记录不存在"));
        workspaceAccess.requireKb(any.getKbId(), workspaceId);
        return repository.findByHistoryGroupIdOrderByVersionDesc(any.getHistoryGroupId()).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public BusinessKnowledgeResponse rollback(Long id, int version, Long workspaceId) {
        BusinessKnowledge current = requireInWorkspace(id, workspaceId);
        BusinessKnowledge target = repository.findByHistoryGroupIdOrderByVersionDesc(current.getHistoryGroupId()).stream()
                .filter(v -> v.getVersion() == version)
                .findFirst()
                .orElseThrow(() -> new BizException("版本不存在"));
        if (target.getId().equals(current.getId())) {
            throw new BizException("当前已是最新版本");
        }
        current.setDeleted(true);
        repository.save(current);
        syncIndex(current);
        target.setDeleted(false);
        target.setStatus(ReviewStatus.APPROVED.value());
        repository.save(target);
        syncIndex(target);
        return toResponse(target);
    }

    private BusinessKnowledge getActive(Long id) {
        return repository.findById(id)
                .filter(b -> !Boolean.TRUE.equals(b.getDeleted()))
                .orElseThrow(() -> new BizException("记录不存在"));
    }

    /** 校验记录存在且其知识库属于当前工作空间（防跨空间按 id 越权） */
    private BusinessKnowledge requireInWorkspace(Long id, Long workspaceId) {
        BusinessKnowledge bk = getActive(id);
        workspaceAccess.requireKb(bk.getKbId(), workspaceId);
        return bk;
    }

    /**
     * ES 索引同步（多源召回）：审核通过（APPROVED）写入索引，软删/拒绝/禁用等移出索引。
     * 幂等：重复调用仅覆盖写入或删除不存在的文档（删除失败仅告警）。实现见 {@link SourceIndexer}。
     */
    private void syncIndex(BusinessKnowledge bk) {
        sourceIndexer.sync(SourceType.BUSINESS.value(), bk.getId(), bk.getKbId(), bk.getSourceDocId(),
                bk.getSourceDocName(), bk.getTerm(), indexContent(bk),
                Boolean.TRUE.equals(bk.getDeleted()), bk.getStatus());
    }

    /** 构建业务知识索引内容：术语/别名/定义/适用范围/示例/禁用规则 */
    private String indexContent(BusinessKnowledge bk) {
        StringBuilder sb = new StringBuilder("术语：").append(bk.getTerm());
        if (!Texts.isBlank(bk.getAliases())) {
            sb.append("\n别名：").append(String.join("、", parseAliases(bk.getAliases())));
        }
        if (!Texts.isBlank(bk.getDefinition())) {
            sb.append("\n定义：").append(bk.getDefinition());
        }
        if (!Texts.isBlank(bk.getScope())) {
            sb.append("\n适用范围：").append(bk.getScope());
        }
        if (!Texts.isBlank(bk.getExample())) {
            sb.append("\n示例：").append(bk.getExample());
        }
        if (!Texts.isBlank(bk.getProhibitedRules())) {
            sb.append("\n禁用规则：").append(bk.getProhibitedRules());
        }
        return sb.toString();
    }

    private void apply(BusinessKnowledge bk, BusinessKnowledgeRequest req) {
        bk.setTerm(req.term());
        bk.setAliases(toJson(req.aliases()));
        bk.setDefinition(req.definition());
        bk.setScope(req.scope());
        bk.setExample(req.example());
        bk.setProhibitedRules(req.prohibitedRules());
        if (req.sourceDocId() != null) {
            documentRepository.findById(req.sourceDocId()).ifPresent(d -> {
                bk.setSourceDocId(d.getId());
                bk.setSourceDocName(d.getFileName());
            });
        }
    }

    public BusinessKnowledgeResponse toResponse(BusinessKnowledge bk) {
        return new BusinessKnowledgeResponse(
                bk.getId(), bk.getKbId(), bk.getTerm(), parseAliases(bk.getAliases()),
                bk.getDefinition(), bk.getScope(), bk.getExample(), bk.getProhibitedRules(),
                bk.getSourceDocId(), bk.getSourceDocName(), bk.getStatus(), bk.getVersion(),
                Boolean.TRUE.equals(bk.getDeleted()), bk.getCreatedAt());
    }

    private List<String> parseAliases(String json) {
        if (Texts.isBlank(json)) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private String toJson(Collection<String> list) {
        if (list == null || list.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(list);
        } catch (Exception e) {
            return null;
        }
    }
}
