package com.ai.konwledgerepo.service.extract;

import com.ai.konwledgerepo.common.PromptCatalog;
import com.ai.konwledgerepo.common.RedisCacheService;
import com.ai.konwledgerepo.common.RedisKeys;
import com.ai.konwledgerepo.config.props.SeuCacheProperties;
import com.ai.konwledgerepo.entity.BusinessKnowledge;
import com.ai.konwledgerepo.entity.Chunk;
import com.ai.konwledgerepo.entity.Document;
import com.ai.konwledgerepo.entity.ExtractTask;
import com.ai.konwledgerepo.entity.KnowledgeBase;
import com.ai.konwledgerepo.model.ModelFactory;
import com.ai.konwledgerepo.repository.BusinessKnowledgeRepository;
import com.ai.konwledgerepo.repository.ChunkRepository;
import com.ai.konwledgerepo.repository.DocumentRepository;
import com.ai.konwledgerepo.repository.ExtractTaskRepository;
import com.ai.konwledgerepo.repository.QaPairRepository;
import com.ai.konwledgerepo.service.knowledge.BusinessKnowledgeService;
import com.ai.konwledgerepo.service.knowledge.QaPairService;
import com.ai.konwledgerepo.service.knowledgebase.WorkspaceIdResolver;
import com.ai.konwledgerepo.tracing.QaTracing;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ExtractTaskExecutor 单元测试。
 * <p>
 * {@code run(Long)} / {@code run(Long, List)} 是 {@code @Async} 方法，但直接调用（非代理实例）
 * 会同步执行，正好用于测试。LlmTrace.call 内部静态调用 chat.call —— 只需 mock ChatModel 即可驱动抽取。
 * <p>
 * 覆盖：全量成功（BOTH 双文档）/ 部分失败 / 全部失败 / 重抽指定文档 + DRAFT 草稿软删 /
 * 无 chunk 文档 / LLM 输出非法 JSON / 任务不存在静默返回 / PROGRESS_DB_BATCH 批量落库。
 */
class ExtractTaskExecutorTest {

    private static final long KB_ID = 1L;
    private static final long WORKSPACE_ID = 1L;
    private static final String BIZ_JSON = "[{\"term\":\"名词\",\"definition\":\"定义\"}]";
    private static final String QA_JSON = "[{\"question\":\"问\",\"answer\":\"答\"}]";

    private ExtractTaskRepository taskRepository;
    private DocumentRepository documentRepository;
    private ChunkRepository chunkRepository;
    private BusinessKnowledgeRepository bkRepository;
    private QaPairRepository qaRepository;
    private BusinessKnowledgeService businessKnowledgeService;
    private QaPairService qaPairService;
    private WorkspaceIdResolver workspaceIdResolver;
    private ModelFactory modelFactory;
    private RedisCacheService redisCacheService;
    private ChatModel chat;
    private ExtractTaskExecutor executor;

    @BeforeEach
    void setUp() {
        taskRepository = mock(ExtractTaskRepository.class);
        documentRepository = mock(DocumentRepository.class);
        chunkRepository = mock(ChunkRepository.class);
        bkRepository = mock(BusinessKnowledgeRepository.class);
        qaRepository = mock(QaPairRepository.class);
        businessKnowledgeService = mock(BusinessKnowledgeService.class);
        qaPairService = mock(QaPairService.class);
        workspaceIdResolver = mock(WorkspaceIdResolver.class);
        modelFactory = mock(ModelFactory.class);
        redisCacheService = mock(RedisCacheService.class);
        chat = mock(ChatModel.class);

        when(workspaceIdResolver.resolve(KB_ID)).thenReturn(WORKSPACE_ID);
        when(modelFactory.getChatModelByUsage(eq("EXTRACT"), eq(WORKSPACE_ID))).thenReturn(chat);
        when(taskRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // QaTracing 真实实例（tracing 关闭 → OTel no-op，无网络）；ObjectMapper 真实实例；
        // TaskProgressStore / 抽取器为真实实例，复用已 mock 的依赖
        QaTracing noopTracing = QaTracing.disabled();
        ObjectMapper objectMapper = new ObjectMapper();
        PromptCatalog promptCatalog = new PromptCatalog();
        ExtractJsonParser jsonParser = new ExtractJsonParser(objectMapper);
        TaskProgressStore progressStore = new TaskProgressStore(redisCacheService,
                new SeuCacheProperties(300, 600, 600, 300, 300, 60, 60, 600, 86400));
        executor = new ExtractTaskExecutor(taskRepository, documentRepository, chunkRepository,
                bkRepository, qaRepository, workspaceIdResolver, modelFactory, progressStore,
                new BusinessKnowledgeExtractor(noopTracing, promptCatalog, businessKnowledgeService, jsonParser),
                new QaPairExtractor(noopTracing, promptCatalog, qaPairService, jsonParser),
                noopTracing, objectMapper);
    }

    // ===== 全量成功 =====

    @Test
    void run_fullSuccess_bothTypes_extractsAllAndWritesFinalProgress() {
        ExtractTask task = task(1L, "[1,2]", "BOTH");
        when(taskRepository.findById(1L)).thenReturn(Optional.of(task));
        when(documentRepository.findById(1L)).thenReturn(Optional.of(document(1L)));
        when(documentRepository.findById(2L)).thenReturn(Optional.of(document(2L)));
        when(chunkRepository.findByDocIdOrderBySeqAsc(1L)).thenReturn(List.of(chunk(1L)));
        when(chunkRepository.findByDocIdOrderBySeqAsc(2L)).thenReturn(List.of(chunk(2L)));
        // 调用顺序：doc1 业务知识 → doc1 问答 → doc2 业务知识 → doc2 问答
        when(chat.call(any(Prompt.class))).thenReturn(
                chatResponse(BIZ_JSON), chatResponse(QA_JSON), chatResponse(BIZ_JSON), chatResponse(QA_JSON));

        executor.run(1L);

        assertEquals("SUCCESS", task.getStatus());
        assertEquals("新增业务知识 2 条，问答对 2 条", task.getResultSummary());
        assertEquals(2, task.getTotalDocs());
        assertEquals(2, task.getProcessedDocs());
        assertEquals(100, task.getProgress());
        assertNotNull(task.getFinishedAt());
        // 落库：doRun 重置 1 次 + 完成 1 次（2 个文档未达 PROGRESS_DB_BATCH=5）
        verify(taskRepository, atLeast(2)).save(any());
        verify(businessKnowledgeService, times(2)).createDraft(eq(KB_ID), any());
        verify(qaPairService, times(2)).createDraft(eq(KB_ID), any());
        // 最终进度 Hash：status=SUCCESS、progress=100、total/processed=2（唯一一次 SUCCESS 写入）
        verify(redisCacheService).hset(eq(RedisKeys.task(1L)),
                argThat((Map<String, String> fields) -> "SUCCESS".equals(fields.get(TaskProgressStore.H_STATUS))
                        && "100".equals(fields.get(TaskProgressStore.H_PROGRESS))
                        && "2".equals(fields.get(TaskProgressStore.H_TOTAL))
                        && "2".equals(fields.get(TaskProgressStore.H_PROCESSED))
                        && fields.get(TaskProgressStore.H_RESULT) != null),
                any());
        verify(redisCacheService, atLeast(3)).hset(eq(RedisKeys.task(1L)), anyMap(), any());
    }

    // ===== 部分失败 =====

    @Test
    void run_partialFailure_oneDocFails_statusPartialFailed() {
        ExtractTask task = task(2L, "[1,2]", "BOTH");
        when(taskRepository.findById(2L)).thenReturn(Optional.of(task));
        when(documentRepository.findById(1L)).thenReturn(Optional.of(document(1L)));
        when(documentRepository.findById(2L)).thenReturn(Optional.of(document(2L)));
        when(chunkRepository.findByDocIdOrderBySeqAsc(1L)).thenReturn(List.of(chunk(1L)));
        when(chunkRepository.findByDocIdOrderBySeqAsc(2L)).thenReturn(List.of(chunk(2L)));
        // 顺序敏感：前 2 次调用成功（doc1 业务知识/问答），第 3 次（doc2 业务知识）抛异常；
        // doc2 问答因异常从 extractDoc 传播后不再调用
        when(chat.call(any(Prompt.class))).thenReturn(chatResponse(BIZ_JSON), chatResponse(QA_JSON))
                .thenThrow(new RuntimeException("LLM 抽取失败"));

        executor.run(2L);

        assertEquals("PARTIAL_FAILED", task.getStatus());
        assertEquals("[2]", task.getFailedDocIds());
        assertNotNull(task.getErrorLog());
        assertTrue(task.getErrorLog().contains("文档 2"));
        assertEquals("新增业务知识 1 条，问答对 1 条", task.getResultSummary());
        assertEquals(2, task.getProcessedDocs());
        assertEquals(100, task.getProgress());
        verify(businessKnowledgeService, times(1)).createDraft(eq(KB_ID), any());
        verify(qaPairService, times(1)).createDraft(eq(KB_ID), any());
        verify(taskRepository, atLeast(2)).save(any());
        verify(redisCacheService, atLeastOnce()).hset(eq(RedisKeys.task(2L)),
                argThat((Map<String, String> fields) -> "[2]".equals(fields.get(TaskProgressStore.H_FAILED))),
                any());
    }

    // ===== 全部失败 =====

    @Test
    void run_allDocsFail_statusFailed() {
        ExtractTask task = task(3L, "[1,2]", "BOTH");
        when(taskRepository.findById(3L)).thenReturn(Optional.of(task));
        when(documentRepository.findById(1L)).thenReturn(Optional.of(document(1L)));
        when(documentRepository.findById(2L)).thenReturn(Optional.of(document(2L)));
        when(chunkRepository.findByDocIdOrderBySeqAsc(1L)).thenReturn(List.of(chunk(1L)));
        when(chunkRepository.findByDocIdOrderBySeqAsc(2L)).thenReturn(List.of(chunk(2L)));
        when(chat.call(any(Prompt.class))).thenThrow(new RuntimeException("LLM 不可用"));

        executor.run(3L);

        assertEquals("FAILED", task.getStatus());
        assertEquals("[1,2]", task.getFailedDocIds());
        assertNotNull(task.getErrorLog());
        assertTrue(task.getErrorLog().contains("文档 1"));
        assertTrue(task.getErrorLog().contains("文档 2"));
        assertEquals("新增业务知识 0 条，问答对 0 条", task.getResultSummary());
        verify(businessKnowledgeService, never()).createDraft(any(), any());
        verify(qaPairService, never()).createDraft(any(), any());
        verify(taskRepository, atLeast(2)).save(any());
    }

    // ===== 重抽路径 =====

    @Test
    void runRetry_onlyProcessesRetryDocsAndSoftDeletesOldDrafts() {
        ExtractTask task = task(4L, "[1,2,3]", "BOTH");
        task.setStatus("PARTIAL_FAILED");
        task.setFailedDocIds("[2]");
        when(taskRepository.findById(4L)).thenReturn(Optional.of(task));
        // 只 stub 重抽文档 2：若实现误处理 1/3，会因“文档不存在”失败并破坏下方断言
        when(documentRepository.findById(2L)).thenReturn(Optional.of(document(2L)));
        when(chunkRepository.findByDocIdOrderBySeqAsc(2L)).thenReturn(List.of(chunk(2L)));
        when(chat.call(any(Prompt.class))).thenReturn(chatResponse(BIZ_JSON), chatResponse(QA_JSON));

        BusinessKnowledge oldDraft = new BusinessKnowledge();
        oldDraft.setId(50L);
        oldDraft.setKbId(KB_ID);
        oldDraft.setStatus("DRAFT");
        when(bkRepository.findBySourceDocIdInAndDeletedFalseAndStatus(anyList(), eq("DRAFT")))
                .thenReturn(List.of(oldDraft));

        executor.run(4L, List.of(2L));

        assertEquals("SUCCESS", task.getStatus());
        assertEquals(1, task.getTotalDocs());
        assertEquals(1, task.getProcessedDocs());
        assertEquals(100, task.getProgress());
        // 只处理了重抽文档 2
        verify(documentRepository, never()).findById(1L);
        verify(documentRepository, never()).findById(3L);
        // 旧 DRAFT 草稿被软删并批量落库
        assertTrue(oldDraft.getDeleted());
        verify(bkRepository).saveAll(List.of(oldDraft));
        verify(businessKnowledgeService, times(1)).createDraft(eq(KB_ID), any());
        verify(qaPairService, times(1)).createDraft(eq(KB_ID), any());
        verify(taskRepository, atLeast(2)).save(any());
    }

    // ===== 无 chunk 文档 =====

    @Test
    void run_docWithoutChunks_skipsLlmAndSucceeds() {
        ExtractTask task = task(5L, "[1,2]", "BOTH");
        when(taskRepository.findById(5L)).thenReturn(Optional.of(task));
        when(documentRepository.findById(1L)).thenReturn(Optional.of(document(1L)));
        when(documentRepository.findById(2L)).thenReturn(Optional.of(document(2L)));
        // 无 chunk：extractDoc 循环体不执行 → 不触发任何 LLM 调用与 createDraft
        when(chunkRepository.findByDocIdOrderBySeqAsc(any())).thenReturn(List.of());

        executor.run(5L);

        assertEquals("SUCCESS", task.getStatus());
        assertEquals("新增业务知识 0 条，问答对 0 条", task.getResultSummary());
        assertEquals(2, task.getProcessedDocs());
        assertEquals(100, task.getProgress());
        verify(chat, never()).call(any(Prompt.class));
        verify(businessKnowledgeService, never()).createDraft(any(), any());
        verify(qaPairService, never()).createDraft(any(), any());
    }

    // ===== LLM 输出非法 JSON =====

    @Test
    void run_invalidLlmJson_fallsBackToEmptyWithoutError() {
        ExtractTask task = task(6L, "[1]", "BOTH");
        when(taskRepository.findById(6L)).thenReturn(Optional.of(task));
        when(documentRepository.findById(1L)).thenReturn(Optional.of(document(1L)));
        when(chunkRepository.findByDocIdOrderBySeqAsc(1L)).thenReturn(List.of(chunk(1L)));
        // LLM 输出非 JSON（无 [ ]）：parseJsonArray 兜底空列表 → 不抛异常、不落库
        when(chat.call(any(Prompt.class))).thenReturn(chatResponse("抱歉，我无法抽取"));

        executor.run(6L);

        assertEquals("SUCCESS", task.getStatus());
        assertEquals("新增业务知识 0 条，问答对 0 条", task.getResultSummary());
        verify(businessKnowledgeService, never()).createDraft(any(), any());
        verify(qaPairService, never()).createDraft(any(), any());
        verify(redisCacheService, atLeast(2)).hset(eq(RedisKeys.task(6L)), anyMap(), any());
    }

    // ===== 任务不存在 =====

    @Test
    void run_taskNotFound_returnsQuietly() {
        when(taskRepository.findById(99L)).thenReturn(Optional.empty());

        executor.run(99L);

        verify(taskRepository, never()).save(any());
        verify(workspaceIdResolver, never()).resolve(any());
        verify(modelFactory, never()).getChatModelByUsage(any(), any());
        verify(redisCacheService, never()).hset(any(), anyMap(), any());
    }

    // ===== PROGRESS_DB_BATCH 批量落库 =====

    @Test
    void run_sixDocs_triggersBatchProgressSave() {
        ExtractTask task = task(7L, "[1,2,3,4,5,6]", "BUSINESS");
        when(taskRepository.findById(7L)).thenReturn(Optional.of(task));
        for (long docId = 1; docId <= 6; docId++) {
            when(documentRepository.findById(docId)).thenReturn(Optional.of(document(docId)));
            when(chunkRepository.findByDocIdOrderBySeqAsc(docId)).thenReturn(List.of(chunk(docId)));
        }
        when(chat.call(any(Prompt.class))).thenReturn(chatResponse(BIZ_JSON));

        executor.run(7L);

        assertEquals("SUCCESS", task.getStatus());
        assertEquals(6, task.getProcessedDocs());
        assertEquals(100, task.getProgress());
        verify(businessKnowledgeService, times(6)).createDraft(eq(KB_ID), any());
        // PROGRESS_DB_BATCH=5：第 5 个文档处理完触发批量落库 → 重置 1 + 批量 1 + 完成 1 = 3 次
        verify(taskRepository, times(3)).save(any());
    }

    // ===== 测试工具 =====

    private ExtractTask task(long id, String docIdsJson, String extractType) {
        ExtractTask t = new ExtractTask();
        t.setId(id);
        t.setKbId(KB_ID);
        t.setDocIds(docIdsJson);
        t.setExtractType(extractType);
        t.setStatus("PENDING");
        return t;
    }

    private Document document(long id) {
        Document d = new Document();
        d.setId(id);
        d.setFileName("doc" + id + ".md");
        return d;
    }

    private Chunk chunk(long docId) {
        Chunk c = new Chunk();
        c.setId(docId * 100L);
        c.setDocId(docId);
        c.setKbId(KB_ID);
        c.setSeq(1);
        c.setContent("第 " + docId + " 个文档的正文内容");
        return c;
    }

    private ChatResponse chatResponse(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }
}
