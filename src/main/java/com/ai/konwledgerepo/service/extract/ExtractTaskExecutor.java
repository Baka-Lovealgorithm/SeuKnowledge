package com.ai.konwledgerepo.service.extract;

import com.ai.konwledgerepo.common.Texts;
import com.ai.konwledgerepo.entity.BusinessKnowledge;
import com.ai.konwledgerepo.entity.Chunk;
import com.ai.konwledgerepo.entity.Document;
import com.ai.konwledgerepo.entity.ExtractTask;
import com.ai.konwledgerepo.entity.ExtractType;
import com.ai.konwledgerepo.entity.ModelUsage;
import com.ai.konwledgerepo.entity.QaPair;
import com.ai.konwledgerepo.entity.ReviewStatus;
import com.ai.konwledgerepo.entity.TaskStatus;
import com.ai.konwledgerepo.model.ModelFactory;
import com.ai.konwledgerepo.repository.BusinessKnowledgeRepository;
import com.ai.konwledgerepo.repository.ChunkRepository;
import com.ai.konwledgerepo.repository.DocumentRepository;
import com.ai.konwledgerepo.repository.ExtractTaskRepository;
import com.ai.konwledgerepo.repository.QaPairRepository;
import com.ai.konwledgerepo.service.knowledgebase.WorkspaceIdResolver;
import com.ai.konwledgerepo.tracing.QaTracing;
import com.ai.konwledgerepo.tracing.TokenAccumulator;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 抽取任务执行器（编排）：逐文档对 chunk 调 LLM 抽取业务知识/问答对，自动入库为 DRAFT 草稿。
 * 独立 bean 承载 @Async，避免自调用不生效问题。
 * 支持重试：{@link #run(Long, List)} 仅重抽指定文档子集（失败文档），
 * 重抽前会软删这些文档下旧的 DRAFT 草稿（已审核的 APPROVED/REJECTED 不受影响）。
 * <ul>
 *   <li>单文档抽取（业务知识 / 问答对）→ {@link BusinessKnowledgeExtractor} / {@link QaPairExtractor}</li>
 *   <li>进度写入 Redis → {@link TaskProgressStore}</li>
 *   <li>LLM 输出解析 → {@link ExtractJsonParser}</li>
 * </ul>
 */
@Service
public class ExtractTaskExecutor {

    private static final Logger log = LoggerFactory.getLogger(ExtractTaskExecutor.class);
    /** 进度批量落库间隔（文档数）；每处理满该数落库一次，减少写放大 */
    private static final int PROGRESS_DB_BATCH = 5;

    private final ExtractTaskRepository taskRepository;
    private final DocumentRepository documentRepository;
    private final ChunkRepository chunkRepository;
    private final BusinessKnowledgeRepository bkRepository;
    private final QaPairRepository qaRepository;
    private final WorkspaceIdResolver workspaceIdResolver;
    private final ModelFactory modelFactory;
    private final TaskProgressStore progressStore;
    private final BusinessKnowledgeExtractor businessExtractor;
    private final QaPairExtractor qaPairExtractor;
    private final QaTracing qaTracing;
    private final ObjectMapper objectMapper;

    public ExtractTaskExecutor(ExtractTaskRepository taskRepository,
                               DocumentRepository documentRepository,
                               ChunkRepository chunkRepository,
                               BusinessKnowledgeRepository bkRepository,
                               QaPairRepository qaRepository,
                               WorkspaceIdResolver workspaceIdResolver,
                               ModelFactory modelFactory,
                               TaskProgressStore progressStore,
                               BusinessKnowledgeExtractor businessExtractor,
                               QaPairExtractor qaPairExtractor,
                               QaTracing qaTracing,
                               ObjectMapper objectMapper) {
        this.taskRepository = taskRepository;
        this.documentRepository = documentRepository;
        this.chunkRepository = chunkRepository;
        this.bkRepository = bkRepository;
        this.qaRepository = qaRepository;
        this.workspaceIdResolver = workspaceIdResolver;
        this.modelFactory = modelFactory;
        this.progressStore = progressStore;
        this.businessExtractor = businessExtractor;
        this.qaPairExtractor = qaPairExtractor;
        this.qaTracing = qaTracing;
        this.objectMapper = objectMapper;
    }

    /** 新建任务：对任务全部文档执行抽取 */
    @Async
    public void run(Long taskId) {
        ExtractTask task = taskRepository.findById(taskId).orElse(null);
        if (task == null) {
            return;
        }
        doRun(task, parseDocIds(task.getDocIds()));
    }

    /** 重试：仅对指定文档（失败文档）重新抽取 */
    @Async
    public void run(Long taskId, List<Long> retryDocIds) {
        ExtractTask task = taskRepository.findById(taskId).orElse(null);
        if (task == null) {
            return;
        }
        doRun(task, retryDocIds);
    }

    private void doRun(ExtractTask task, List<Long> docIds) {
        // 新一轮执行：重置任务运行态
        task.setStatus(TaskStatus.RUNNING.value());
        task.setTotalDocs(docIds.size());
        task.setProcessedDocs(0);
        task.setProgress(0);
        task.setErrorLog(null);
        task.setResultSummary(null);
        task.setFinishedAt(null);
        task.setFailedDocIds(null);
        task.setDurationMs(null);
        task.setTokenTotal(null);
        task.setTokenInput(null);
        task.setTokenOutput(null);
        taskRepository.save(task);
        progressStore.write(task);

        // 重抽前清理待抽文档下旧的 DRAFT 草稿，避免重复堆积（已审核的不动）
        cleanupDrafts(docIds);

        // OpenTelemetry trace（Langfuse 可视化）：根 span extract/task，LLM 调用按 generation 子 span 统计 token
        Span root = qaTracing.begin("extract/task");
        root.setAttribute("task.id", task.getId());
        root.setAttribute("kb.id", task.getKbId());
        root.setAttribute("langfuse.trace.name", "抽取任务: " + task.getId());
        root.setAttribute("langfuse.span.type", "TASK");
        TokenAccumulator.begin();
        long start = System.currentTimeMillis();

        List<Long> failedDocIds = new ArrayList<>();
        int processed = 0;
        int bkCount = 0;
        int qaCount = 0;
        boolean hasSuccess = false;
        boolean hasFail = false;
        StringBuilder errors = new StringBuilder();

        try (Scope scope = root.makeCurrent()) {
            // 按知识库归属解析工作空间（抽取模型按空间解析）
            Long workspaceId = workspaceIdResolver.resolve(task.getKbId());
            ChatModel chat = modelFactory.getChatModelByUsage(ModelUsage.EXTRACT.value(), workspaceId);
            for (Long docId : docIds) {
                try {
                    Counts counts = extractDoc(chat, task.getKbId(), docId, task.getExtractType());
                    bkCount += counts.business();
                    qaCount += counts.qa();
                    hasSuccess = true;
                } catch (Exception e) {
                    hasFail = true;
                    failedDocIds.add(docId);
                    errors.append("文档 ").append(docId).append(": ").append(Texts.truncate(e.getMessage(), 200)).append("\n");
                    log.warn("抽取文档 {} 失败", docId, e);
                }
                processed++;
                task.setProcessedDocs(processed);
                task.setProgress(docIds.isEmpty() ? 100 : processed * 100 / docIds.size());
                task.setErrorLog(errors.isEmpty() ? null : errors.toString());
                task.setFailedDocIds(toJson(failedDocIds));
                // 进度实时写 Redis（前端轮询秒级可见），DB 批量落库减少写放大
                progressStore.write(task);
                if (processed % PROGRESS_DB_BATCH == 0) {
                    taskRepository.save(task);
                }
            }

            // 统计落库：耗时 + LLM token（text 类累计），并写入 trace 根 span 属性
            long[] totals = TokenAccumulator.totals();
            task.setDurationMs(System.currentTimeMillis() - start);
            task.setTokenInput(totals[0]);
            task.setTokenOutput(totals[1]);
            task.setTokenTotal(totals[2]);
            task.setResultSummary("新增业务知识 " + bkCount + " 条，问答对 " + qaCount + " 条");
            task.setStatus(hasFail && hasSuccess ? TaskStatus.PARTIAL_FAILED.value()
                    : hasFail ? TaskStatus.FAILED.value() : TaskStatus.SUCCESS.value());
            task.setFinishedAt(LocalDateTime.now());
            taskRepository.save(task);
            progressStore.write(task);
            log.info("抽取任务 {} 完成：{}（耗时 {}ms，token {}）",
                    task.getId(), task.getResultSummary(), task.getDurationMs(), task.getTokenTotal());
        } catch (Exception e) {
            hasFail = true;
            errors.append("任务中止: ").append(Texts.truncate(e.getMessage(), 300)).append("\n");
            task.setErrorLog(errors.toString());
            root.recordException(e);
            log.error("抽取任务 {} 中止", task.getId(), e);
        } finally {
            TokenAccumulator.flushToSpan(root);
            root.end();
        }
    }

    /** 软删指定文档下未审核的 DRAFT 草稿（抽取任务产物，重新抽取前作废旧稿） */
    private void cleanupDrafts(List<Long> docIds) {
        if (docIds == null || docIds.isEmpty()) {
            return;
        }
        List<BusinessKnowledge> bks = bkRepository.findBySourceDocIdInAndDeletedFalseAndStatus(docIds,
                ReviewStatus.DRAFT.value());
        if (!bks.isEmpty()) {
            bks.forEach(b -> b.setDeleted(true));
            bkRepository.saveAll(bks);
            log.info("清理业务知识 DRAFT 草稿 {} 条（文档 {}）", bks.size(), docIds);
        }
        List<QaPair> qas = qaRepository.findBySourceDocIdInAndDeletedFalseAndStatus(docIds,
                ReviewStatus.DRAFT.value());
        if (!qas.isEmpty()) {
            qas.forEach(q -> q.setDeleted(true));
            qaRepository.saveAll(qas);
            log.info("清理问答对 DRAFT 草稿 {} 条（文档 {}）", qas.size(), docIds);
        }
    }

    private Counts extractDoc(ChatModel chat, Long kbId, Long docId, String extractType) {
        Document doc = documentRepository.findById(docId)
                .orElseThrow(() -> new IllegalStateException("文档不存在: " + docId));
        List<Chunk> chunks = chunkRepository.findByDocIdOrderBySeqAsc(docId);
        boolean doBusiness = ExtractType.BUSINESS.is(extractType) || ExtractType.BOTH.is(extractType);
        boolean doQa = ExtractType.QA.is(extractType) || ExtractType.BOTH.is(extractType);

        int business = 0;
        int qa = 0;
        for (Chunk chunk : chunks) {
            if (doBusiness) {
                business += businessExtractor.extract(chat, kbId, doc, chunk);
            }
            if (doQa) {
                qa += qaPairExtractor.extract(chat, kbId, doc, chunk);
            }
        }
        return new Counts(business, qa);
    }

    private List<Long> parseDocIds(String json) {
        if (Texts.isBlank(json)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<Long>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }

    private String toJson(List<Long> ids) {
        try {
            return objectMapper.writeValueAsString(ids == null ? List.of() : ids);
        } catch (Exception e) {
            return "[]";
        }
    }

    private record Counts(int business, int qa) {
    }
}
