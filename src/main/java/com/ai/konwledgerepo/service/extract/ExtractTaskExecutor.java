package com.ai.konwledgerepo.service.extract;

import com.ai.konwledgerepo.common.JsonLists;
import com.ai.konwledgerepo.common.RedisKeys;
import com.ai.konwledgerepo.common.TaskLock;
import com.ai.konwledgerepo.common.Texts;
import com.ai.konwledgerepo.config.props.SeuExtractProperties;
import com.ai.konwledgerepo.entity.BusinessKnowledge;
import com.ai.konwledgerepo.entity.Chunk;
import com.ai.konwledgerepo.entity.DocStatus;
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
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;

/**
 * 抽取任务执行器（编排）：逐文档对 chunk 调 LLM 抽取业务知识/问答对，自动入库为 DRAFT 草稿。
 * 独立 bean 承载 @Async，避免自调用不生效问题。
 * <p>
 * 并发安全增强（E-1 / E-2 / E-4 / E-6）：
 * <ul>
 *   <li>E-6 全局抽取并发预算：Semaphore（KB_EXTRACT_CONCURRENCY），限同时运行的抽取任务数。</li>
 *   <li>E-1 任务级互斥：Redis SETNX taskRun 锁 + DB 悲观锁 startRun（FOR UPDATE 原子抢占 RUNNING）。</li>
 *   <li>E-2 文档级互斥：逐文档 SETNX taskExtract 锁，防止并发任务清理/抽取同一文档的草稿。</li>
 *   <li>E-4 文档状态校验：extractDoc 仅处理 parse_status=SUCCESS 的文档，未完成解析的标记失败。</li>
 * </ul>
 */
@Service
public class ExtractTaskExecutor {

    private static final Logger log = LoggerFactory.getLogger(ExtractTaskExecutor.class);
    /** 进度批量落库间隔（文档数）；每处理满该数落库一次，减少写放大 */
    private static final int PROGRESS_DB_BATCH = 5;
    private static final Duration TASK_RUN_TTL = Duration.ofHours(8);
    private static final Duration DOC_EXTRACT_TTL = Duration.ofHours(8);

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
    private final TaskLock taskLock;
    private final ExtractTaskTx extractTaskTx;
    private final Semaphore extractSemaphore;

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
                               ObjectMapper objectMapper,
                               TaskLock taskLock,
                               ExtractTaskTx extractTaskTx,
                               SeuExtractProperties extractProps) {
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
        this.taskLock = taskLock;
        this.extractTaskTx = extractTaskTx;
        this.extractSemaphore = new Semaphore(Math.max(1, extractProps.concurrencyLimit()), true);
    }

    /** 新建任务：对任务全部文档执行抽取 */
    @Async
    public void run(Long taskId) {
        runInternal(taskId, null);
    }

    /** 重试：仅对指定文档（失败文档）重新抽取 */
    @Async
    public void run(Long taskId, List<Long> retryDocIds) {
        runInternal(taskId, retryDocIds);
    }

    // ---- E-6 + E-1 入口守卫 ----

    private void runInternal(Long taskId, List<Long> retryDocIds) {
        // E-6 全局抽取并发预算
        try {
            extractSemaphore.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("抽取任务 {} 被中断（并发预算等待）", taskId);
            return;
        }
        boolean taskLocked = false;
        try {
            // E-1 任务级互斥（快速路径）
            if (!taskLock.tryAcquire(RedisKeys.taskRun(taskId), TASK_RUN_TTL)) {
                log.info("抽取任务 {} 已在执行中，跳过本次触发", taskId);
                return;
            }
            taskLocked = true;
            // E-1 防线二：DB 悲观锁 + 状态守卫（原子抢占 RUNNING）
            ExtractTask task = extractTaskTx.startRun(taskId);
            if (task == null) {
                return; // 任务不存在或已在执行中
            }
            List<Long> docIds = retryDocIds == null ? parseDocIds(task.getDocIds()) : retryDocIds;
            doRun(task, docIds);
        } finally {
            if (taskLocked) {
                taskLock.release(RedisKeys.taskRun(taskId));
            }
            extractSemaphore.release();
        }
    }

    // ---- 核心逻辑 ----

    private void doRun(ExtractTask task, List<Long> docIds) {
        // 新一轮执行：重置任务运行态（RUNNING 已由 extractTaskTx.startRun 置好）
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

        // OpenTelemetry trace（Langfuse 可视化）
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
            Long workspaceId = workspaceIdResolver.resolve(task.getKbId());
            ChatModel chat = modelFactory.getChatModelByUsage(ModelUsage.EXTRACT.value(), workspaceId);
            for (Long docId : docIds) {
                // ---- E-2 文档级互斥锁 ----
                boolean docLocked = taskLock.tryAcquire(RedisKeys.taskExtract(docId), DOC_EXTRACT_TTL);
                if (!docLocked) {
                    hasFail = true;
                    failedDocIds.add(docId);
                    errors.append("文档 ").append(docId).append(": 正在被其他抽取任务处理\n");
                    log.warn("文档 {} 正在被其他抽取任务处理，本任务跳过", docId);
                    processed++;
                    task.setProcessedDocs(processed);
                    task.setProgress(docIds.isEmpty() ? 100 : processed * 100 / docIds.size());
                    task.setErrorLog(errors.isEmpty() ? null : errors.toString());
                    task.setFailedDocIds(toJson(failedDocIds));
                    progressStore.write(task);
                    continue;
                }
                try {
                    // ---- E-2 文档锁内：清理该文档旧草稿 + 抽取 ----
                    cleanupDrafts(List.of(docId));
                    Counts counts = extractDoc(chat, task.getKbId(), docId, task.getExtractType());
                    bkCount += counts.business();
                    qaCount += counts.qa();
                    hasSuccess = true;
                } catch (Exception e) {
                    hasFail = true;
                    failedDocIds.add(docId);
                    errors.append("文档 ").append(docId).append(": ").append(Texts.truncate(e.getMessage(), 200)).append("\n");
                    log.warn("抽取文档 {} 失败", docId, e);
                } finally {
                    taskLock.release(RedisKeys.taskExtract(docId));
                }
                processed++;
                task.setProcessedDocs(processed);
                task.setProgress(docIds.isEmpty() ? 100 : processed * 100 / docIds.size());
                task.setErrorLog(errors.isEmpty() ? null : errors.toString());
                task.setFailedDocIds(toJson(failedDocIds));
                progressStore.write(task);
                if (processed % PROGRESS_DB_BATCH == 0) {
                    taskRepository.save(task);
                }
            }

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

    // ---- E-4 文档状态校验 ----

    private Counts extractDoc(ChatModel chat, Long kbId, Long docId, String extractType) {
        Document doc = documentRepository.findById(docId)
                .orElseThrow(() -> new IllegalStateException("文档不存在: " + docId));
        if (!DocStatus.SUCCESS.is(doc.getParseStatus())) {
            throw new IllegalStateException("文档未完成解析（parse_status=" + doc.getParseStatus() + "），本次抽取跳过");
        }
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
        return JsonLists.readLongsOrEmpty(json, objectMapper);
    }

    private String toJson(List<Long> ids) {
        return JsonLists.writeOrEmptyArray(ids, objectMapper);
    }

    private record Counts(int business, int qa) {
    }
}