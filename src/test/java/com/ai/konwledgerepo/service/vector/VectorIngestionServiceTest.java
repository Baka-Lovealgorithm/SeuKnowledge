package com.ai.konwledgerepo.service.vector;

import co.elastic.clients.elasticsearch._types.Conflicts;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.DeleteByQueryRequest;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import co.elastic.clients.elasticsearch.core.bulk.OperationType;
import com.ai.konwledgerepo.config.props.SeuEsProperties;
import com.ai.konwledgerepo.entity.Chunk;
import com.ai.konwledgerepo.entity.ChunkStatus;
import com.ai.konwledgerepo.entity.Document;
import com.ai.konwledgerepo.entity.SourceType;
import com.ai.konwledgerepo.model.ModelFactory;
import com.ai.konwledgerepo.repository.ChunkRepository;
import com.ai.konwledgerepo.repository.DocumentRepository;
import com.ai.konwledgerepo.service.knowledgebase.WorkspaceIdResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.embedding.EmbeddingModel;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 向量化服务测试：标题拼接进入 embedding 输入（knn 检索感知标题）。
 * 说明：ElasticsearchClient 继承自非 public 父类，Mockito 无法 stub 其方法，
 * 故 ES 写入路径不 mock ES client（成功路径由 {@link #embedText} / {@link #embedTexts}
 * 纯逻辑用例覆盖），错误/短路路径（不触达 ES）用 mock 验证。
 */
class VectorIngestionServiceTest {

    private ChunkRepository chunkRepository;
    private DocumentRepository documentRepository;
    private WorkspaceIdResolver workspaceIdResolver;
    private ModelFactory modelFactory;
    private EmbeddingModel embeddingModel;
    private VectorIngestionService service;

    @BeforeEach
    void setUp() {
        // esClient 无法被 Mockito mock（父类非 public），传 null：被测试方法在 ES 写入前即返回/抛错，不触达
        chunkRepository = mock(ChunkRepository.class);
        documentRepository = mock(DocumentRepository.class);
        workspaceIdResolver = mock(WorkspaceIdResolver.class);
        modelFactory = mock(ModelFactory.class);
        embeddingModel = mock(EmbeddingModel.class);
        service = new VectorIngestionService(null, chunkRepository, documentRepository,
                workspaceIdResolver, modelFactory, new SeuEsProperties("kb_chunk", 1024));
        when(workspaceIdResolver.resolve(any(Long.class))).thenReturn(10L);
        when(modelFactory.getEmbeddingModelByUsage(anyString(), anyLong())).thenReturn(embeddingModel);
    }

    // ===== 文档删除：ES 查询边界 =====

    @Test
    void documentChunkQuery_matchesOnlyChunkAndLegacyChunk() {
        Query query = VectorIngestionService.documentChunkQuery(7L);

        assertTrue(query.isBool());
        List<Query> filters = query.bool().filter();
        assertEquals(2, filters.size());
        assertEquals(ChunkDocFields.DOC_ID, filters.get(0).term().field());
        assertEquals(7L, filters.get(0).term().value().longValue());

        Query sourceTypes = filters.get(1);
        assertEquals("1", sourceTypes.bool().minimumShouldMatch());
        assertEquals(2, sourceTypes.bool().should().size());
        assertEquals(SourceType.CHUNK.value(),
                sourceTypes.bool().should().get(0).term().value().stringValue());
        Query legacyWithoutSourceType = sourceTypes.bool().should().get(1);
        assertEquals(ChunkDocFields.SOURCE_TYPE,
                legacyWithoutSourceType.bool().mustNot().get(0).exists().field());
    }

    @Test
    void structuredSourceQuery_matchesBusinessAndQaButNotChunk() {
        Query query = VectorIngestionService.structuredSourceQuery(7L);

        List<Query> filters = query.bool().filter();
        assertEquals(7L, filters.get(0).term().value().longValue());
        List<String> sourceTypes = filters.get(1).bool().should().stream()
                .map(q -> q.term().value().stringValue())
                .toList();
        assertEquals(List.of(SourceType.BUSINESS.value(), SourceType.QA.value()), sourceTypes);
    }

    // ===== embedText：标题拼接核心逻辑 =====

    @Test
    void embedText_titleAndContent_concatenated() {
        assertEquals("3.2 配置 QoS\n正文内容", VectorIngestionService.embedText("3.2 配置 QoS", "正文内容"));
    }

    @Test
    void embedText_blankTitle_returnsContentAlone() {
        assertEquals("正文内容", VectorIngestionService.embedText("  ", "正文内容"));
    }

    @Test
    void embedText_nullTitle_returnsContentAlone() {
        assertEquals("正文内容", VectorIngestionService.embedText(null, "正文内容"));
    }

    @Test
    void embedText_blankContent_returnsContent() {
        assertNull(VectorIngestionService.embedText("标题", null));
        assertEquals("", VectorIngestionService.embedText("标题", ""));
    }

    // ===== embedTexts：chunk 批量映射（替代 ingest 成功路径的文本构造验证）=====

    @Test
    void embedTexts_mixedTitles_mapsEachChunk() {
        List<Chunk> chunks = List.of(
                chunk(1L, "3.2 配置 QoS", "正文一"),
                chunk(2L, null, "正文二"),
                chunk(3L, "  ", "正文三"));

        List<String> texts = VectorIngestionService.embedTexts(chunks);

        assertEquals(3, texts.size());
        assertEquals("3.2 配置 QoS\n正文一", texts.get(0), "带标题 chunk 应拼接标题进向量化文本");
        assertEquals("正文二", texts.get(1), "null 标题 chunk 应回退纯正文");
        assertEquals("正文三", texts.get(2), "空白标题 chunk 应回退纯正文");
    }

    // ===== ingest：embedding 失败路径（不触达 ES）=====

    @Test
    void ingest_embeddingFailure_marksDocumentError() {
        Chunk c = chunk(1L, "标题", "正文");
        when(documentRepository.findById(1L)).thenReturn(Optional.of(doc()));
        when(chunkRepository.findByDocIdOrderBySeqAsc(1L)).thenReturn(List.of(c));
        when(embeddingModel.embed(anyList())).thenThrow(new RuntimeException("embed 失败"));

        service.ingest(1L);

        ArgumentCaptor<Document> captor = ArgumentCaptor.forClass(Document.class);
        verify(documentRepository).save(captor.capture());
        assertEquals("ERROR", captor.getValue().getParseStatus(), "embedding 失败应将文档置为 ERROR");
    }

    // ===== DEFER 决策：SUSPECT chunk 跳过自动向量化 =====

    @Test
    void ingest_suspectChunk_skippedWithoutEmbedding() {
        Chunk suspect = chunk(1L, "标题", "正文");
        suspect.setCleanStatus("SUSPECT");
        when(documentRepository.findById(1L)).thenReturn(Optional.of(doc()));
        when(chunkRepository.findByDocIdOrderBySeqAsc(1L)).thenReturn(List.of(suspect));

        service.ingest(1L);

        verify(embeddingModel, never()).embed(anyList());
        // 回写照常发生（ SUSPECT 不计入分母 → 摘要为 null → 不误报"向量未完成"），但向量态必须被明确落库
        ArgumentCaptor<Document> captor = ArgumentCaptor.forClass(Document.class);
        verify(documentRepository).save(captor.capture());
        assertNull(captor.getValue().getErrorMsg(), "全是待人工审核块不应写失败摘要");
    }

    // ===== 向量态收尾回写：修"文档显示成功却检索不到"的静默不一致 =====

    @Test
    void ingest_modelNotConfigured_writesActionableErrorMsg() {
        when(documentRepository.findById(1L)).thenReturn(Optional.of(doc()));
        when(chunkRepository.findByDocIdOrderBySeqAsc(1L)).thenReturn(List.of(chunk(1L, "标题", "正文")));
        when(modelFactory.getEmbeddingModelByUsage(anyString(), anyLong()))
                .thenThrow(new IllegalStateException("未配置 EMBEDDING 模型"));

        service.ingest(1L);

        ArgumentCaptor<Document> captor = ArgumentCaptor.forClass(Document.class);
        verify(documentRepository).save(captor.capture());
        // 此前这里只 log.warn 后 return：文档停在 SUCCESS、块停在 EMBEDDING，用户永远看不出来
        assertTrue(captor.getValue().getErrorMsg().contains("向量模型未配置"), captor.getValue().getErrorMsg());
        assertTrue(captor.getValue().getErrorMsg().contains("重建向量"), "应给出可动手的救济指引");
    }

    @Test
    void ingest_partialIndexFailure_surfacesOnDocument() {
        Chunk indexed = chunk(1L, "标题", "已索引正文");
        indexed.setStatus(ChunkStatus.INDEXED.value());
        Chunk failed = chunk(2L, "标题", "失败正文");
        failed.setStatus(ChunkStatus.FAILED.value());
        Document doc = doc();
        doc.setParseStatus("SUCCESS");
        when(documentRepository.findById(1L)).thenReturn(Optional.of(doc));
        // 无 EMBEDDING 块 → 不触 ES，直接走收尾回写（对应"bulk 部分失败后文档仍显示健康"的场景）
        when(chunkRepository.findByDocIdOrderBySeqAsc(1L)).thenReturn(List.of(indexed, failed));

        service.ingest(1L);

        ArgumentCaptor<Document> captor = ArgumentCaptor.forClass(Document.class);
        verify(documentRepository).save(captor.capture());
        assertEquals("SUCCESS", captor.getValue().getParseStatus(), "解析确实成功，不新增状态取值");
        assertTrue(captor.getValue().getErrorMsg().contains("已索引 1/2"), captor.getValue().getErrorMsg());
        assertTrue(captor.getValue().getErrorMsg().contains("失败 1"), captor.getValue().getErrorMsg());
    }

    @Test
    void ingest_allIndexed_recoversErrorToSuccessAndClearsErrorMsg() {
        Chunk indexed = chunk(1L, "标题", "正文");
        indexed.setStatus(ChunkStatus.INDEXED.value());
        Document doc = doc();
        doc.setParseStatus("ERROR");
        doc.setErrorMsg("向量化失败: 上游超时");
        when(documentRepository.findById(1L)).thenReturn(Optional.of(doc));
        when(chunkRepository.findByDocIdOrderBySeqAsc(1L)).thenReturn(List.of(indexed));

        service.ingest(1L);

        ArgumentCaptor<Document> captor = ArgumentCaptor.forClass(Document.class);
        verify(documentRepository).save(captor.capture());
        // ERROR 只由本类的向量化失败写入 → 重建向量追平后应自动复位，否则用户会对着一条陈旧 ERROR 无从下手
        assertEquals("SUCCESS", captor.getValue().getParseStatus(), "向量全部追平时应复位 ERROR");
        assertNull(captor.getValue().getErrorMsg());
    }

    @Test
    void ingest_embeddingFailure_keepsTruncatedErrorMsg() {
        Chunk c = chunk(1L, "标题", "正文");
        when(documentRepository.findById(1L)).thenReturn(Optional.of(doc()));
        when(chunkRepository.findByDocIdOrderBySeqAsc(1L)).thenReturn(List.of(c));
        // 远超 error_msg varchar(500) 的异常信息：截断防止落库二次抛完整性异常
        when(embeddingModel.embed(anyList())).thenThrow(new RuntimeException("x".repeat(900)));

        service.ingest(1L);

        ArgumentCaptor<Document> captor = ArgumentCaptor.forClass(Document.class);
        verify(documentRepository).save(captor.capture());
        assertEquals("ERROR", captor.getValue().getParseStatus(), "embedding 失败应将文档置为 ERROR");
        assertTrue(captor.getValue().getErrorMsg().length() <= 500,
                "errorMsg 必须截断到列宽内，实际 " + captor.getValue().getErrorMsg().length());
    }

    // ===== vectorStateSummary / truncate：纯函数 =====

    @Test
    void vectorStateSummary_allHealthy_returnsNull() {
        assertNull(VectorIngestionService.vectorStateSummary(12, 0, 0, 0), "全健康应清空 errorMsg");
        assertNull(VectorIngestionService.vectorStateSummary(0, 0, 0, 3), "只剩待人工审核不算向量化未完成");
    }

    @Test
    void vectorStateSummary_pendingAndFailedAndSuspect_listsAll() {
        String summary = VectorIngestionService.vectorStateSummary(12, 2, 1, 3);

        assertTrue(summary.contains("已索引 12/15"), summary);
        assertTrue(summary.contains("失败 1"), summary);
        assertTrue(summary.contains("待向量化 2"), summary);
        assertTrue(summary.contains("另有 3 块待人工审核"), summary);
        assertTrue(summary.contains("重建向量"), summary);
    }

    @Test
    void truncate_keepsShortAndLimitsLong() {
        assertEquals("abc", VectorIngestionService.truncate("abc", 500));
        assertNull(VectorIngestionService.truncate(null, 500));
        String out = VectorIngestionService.truncate("y".repeat(600), 500);
        assertEquals(500, out.length());
        assertTrue(out.endsWith("…"));
    }

    @Test
    void ingest_mixedKeepAndSuspect_onlyKeepEmbedded() {
        Chunk keep = chunk(1L, "标题", "保留正文");
        Chunk suspect = chunk(2L, "标题", "可疑正文");
        suspect.setCleanStatus("SUSPECT");
        when(documentRepository.findById(1L)).thenReturn(Optional.of(doc()));
        when(chunkRepository.findByDocIdOrderBySeqAsc(1L)).thenReturn(List.of(keep, suspect));
        // embed 抛异常以在 ES 写入前终止（esClient 不可 mock），验证入参批次只含 KEEP
        when(embeddingModel.embed(anyList())).thenThrow(new RuntimeException("停止"));

        service.ingest(1L);

        ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
        verify(embeddingModel).embed(captor.capture());
        assertEquals(1, captor.getValue().size(), "仅 KEEP chunk 进入 embedding 批");
        assertEquals("标题\n保留正文", captor.getValue().get(0), "SUSPECT 不应进入向量化批次");
    }

    // ===== reindexChunk：单 chunk 向量化（人工审核触发）=====

    @Test
    void reindexChunk_blankContent_rejected() {
        Chunk c = chunk(1L, "标题", "  ");
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> service.reindexChunk(c));
    }

    @Test
    void reindexChunk_docMissing_throws() {
        Chunk c = chunk(1L, "标题", "正文");
        when(documentRepository.findById(1L)).thenReturn(Optional.empty());
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> service.reindexChunk(c));
    }

    @Test
    void reindexChunk_embeddingFailure_throwsBizException() {
        Chunk c = chunk(1L, "标题", "正文");
        when(documentRepository.findById(1L)).thenReturn(Optional.of(doc()));
        when(embeddingModel.embed(anyString())).thenThrow(new RuntimeException("embed 失败"));

        org.junit.jupiter.api.Assertions.assertThrows(com.ai.konwledgerepo.common.BizException.class,
                () -> service.reindexChunk(c));
        assertEquals(ChunkStatus.EMBEDDING.value(), c.getStatus(), "失败时 chunk 状态不变");
    }

    // ===== partitionBySize：bulk 分块边界（纯逻辑，不依赖 ES client）=====

    @Test
    void partitionBySize_emptyList_returnsNoBatches() {
        assertTrue(VectorIngestionService.partitionBySize(List.of(), 100).isEmpty());
    }

    @Test
    void partitionBySize_lessThanBatch_returnsSingleBatch() {
        List<Chunk> chunks = List.of(chunk(1L, "t", "c"), chunk(2L, "t", "c"));
        List<List<Chunk>> batches = VectorIngestionService.partitionBySize(chunks, 100);
        assertEquals(1, batches.size(), "不足一批应只分一批");
        assertEquals(List.of(1L, 2L), ids(batches.get(0)));
    }

    @Test
    void partitionBySize_exactlyBatchSize_returnsSingleBatch() {
        List<Chunk> chunks = List.of(chunk(1L, "t", "c"), chunk(2L, "t", "c"), chunk(3L, "t", "c"));
        List<List<Chunk>> batches = VectorIngestionService.partitionBySize(chunks, 3);
        assertEquals(1, batches.size(), "恰好一批应只分一批");
        assertEquals(3, batches.get(0).size());
    }

    @Test
    void partitionBySize_exceedsBatch_partitionsWithCorrectTail() {
        List<Chunk> chunks = List.of(chunk(1L, "t", "c"), chunk(2L, "t", "c"), chunk(3L, "t", "c"),
                chunk(4L, "t", "c"), chunk(5L, "t", "c"));
        List<List<Chunk>> batches = VectorIngestionService.partitionBySize(chunks, 2);
        assertEquals(3, batches.size(), "5 条按 2/批应分 3 批");
        assertEquals(List.of(1L, 2L), ids(batches.get(0)));
        assertEquals(List.of(3L, 4L), ids(batches.get(1)));
        assertEquals(List.of(5L), ids(batches.get(2)), "末批应为剩余 1 条且保持顺序");
    }

    @Test
    void partitionBySize_preservesOriginalOrderAndReferences() {
        List<Chunk> chunks = new java.util.ArrayList<>(List.of(
                chunk(10L, "t", "c"), chunk(20L, "t", "c"), chunk(30L, "t", "c"), chunk(40L, "t", "c")));
        List<List<Chunk>> batches = VectorIngestionService.partitionBySize(chunks, 2);
        List<Chunk> flattened = batches.stream().flatMap(List::stream).toList();
        assertEquals(4, flattened.size());
        for (int i = 0; i < chunks.size(); i++) {
            assertSame(chunks.get(i), flattened.get(i), "分块应保持元素引用与顺序");
        }
    }

    // ===== indexSource：内容为空短路（不触达 ES）=====

    @Test
    void indexSource_blankContent_skipsEmbedding() {
        boolean ok = service.indexSource("BUSINESS", 7L, 1L, null, "test.md", 0, "标题", "  ");

        assertEquals(false, ok, "内容为空不应写入");
        verify(embeddingModel, never()).embed(anyString());
    }

    // ===== ES 删除请求口径：conflicts=Proceed（同文件两处 updateByQuery 早就设了，删除漏设） =====

    @Test
    void deleteByDocIdRequest_proceedsOnConflictsAndTargetsChunkOnly() {
        DeleteByQueryRequest req = VectorIngestionService.deleteByDocIdRequest("kb_chunk", 7L);

        assertEquals(List.of("kb_chunk"), req.index());
        assertEquals(Conflicts.Proceed, req.conflicts(),
                "删除是幂等操作：并发向量化时的版本冲突不该让整个 delete_by_query 以 409 失败");
        Query query = req.query();
        assertTrue(query.isBool(), "按文档删除要带 sourceType 限定（BUSINESS/QA 也带 docId）");
        assertEquals(7L, query.bool().filter().get(0).term().value().longValue());
    }

    @Test
    void deleteByKbIdRequest_proceedsOnConflictsAndMatchesKbIdField() {
        DeleteByQueryRequest req = VectorIngestionService.deleteByKbIdRequest("kb_chunk", 3L);

        assertEquals(Conflicts.Proceed, req.conflicts());
        assertEquals(ChunkDocFields.KB_ID, req.query().term().field(), "字段名走常量，别再写字面量");
        assertEquals(3L, req.query().term().value().longValue());
    }

    // ===== bulk 失败兜底：ES 返回错误码（RuntimeException）不能冒出 ingest =====

    /**
     * ES「连不上」抛 IOException、「返回错误码」抛 ElasticsearchException（RuntimeException）。
     * 以前只 catch IOException → 后者会穿出 ingest，而 ingestAsync 是 @Async void 且全仓没有
     * AsyncUncaughtExceptionHandler，异常只落一行日志，并连带跳过 applyVectorState ——
     * 文档停在 SUCCESS、errorMsg 空、chunk 永停 EMBEDDING，正是本类 javadoc 承诺已消灭的形态。
     * ElasticsearchClient 无法被 Mockito mock（父类非 public），故覆盖 {@code callBulk} 注入失败。
     */
    @Test
    void ingest_bulkThrowsRuntimeException_marksBatchFailedAndStillWritesVectorState() {
        Chunk c = chunk(1L, "标题", "正文");
        Document doc = doc();
        when(documentRepository.findById(1L)).thenReturn(Optional.of(doc));
        when(chunkRepository.findByDocIdOrderBySeqAsc(1L)).thenReturn(List.of(c));
        when(embeddingModel.embed(anyList())).thenReturn(List.of(new float[]{0.1f}));
        VectorIngestionService failing = new VectorIngestionService(null, chunkRepository, documentRepository,
                workspaceIdResolver, modelFactory, new SeuEsProperties("kb_chunk", 1024)) {
            @Override
            BulkResponse callBulk(BulkRequest request) {
                throw new IllegalStateException("模拟 ElasticsearchException：cluster_block_exception index read-only");
            }
        };

        failing.ingest(1L);

        assertEquals(ChunkStatus.FAILED.value(), c.getStatus(), "ES 错误码要与连不上同处理：整批置 FAILED");
        ArgumentCaptor<Document> captor = ArgumentCaptor.forClass(Document.class);
        verify(documentRepository).save(captor.capture());
        assertTrue(captor.getValue().getErrorMsg().contains("向量未完成"),
                "异常没有绕过收尾回写：" + captor.getValue().getErrorMsg());
        assertTrue(captor.getValue().getErrorMsg().contains("重建向量"), "要留下可动手的救济指引");
    }

    /** 对照：bulk 成功时 chunk 应 INDEXED，证明上一条的 FAILED 来自 catch 分支而非"根本没跑到" */
    @Test
    void ingest_bulkSucceeds_marksChunkIndexed() {
        Chunk c = chunk(1L, "标题", "正文");
        Document doc = doc();
        when(documentRepository.findById(1L)).thenReturn(Optional.of(doc));
        when(chunkRepository.findByDocIdOrderBySeqAsc(1L)).thenReturn(List.of(c));
        when(embeddingModel.embed(anyList())).thenReturn(List.of(new float[]{0.1f}));
        BulkResponseItem okItem = BulkResponseItem.of(i -> i.id("1").index("kb_chunk")
                .status(201).operationType(OperationType.Index));
        BulkResponse ok = BulkResponse.of(b -> b.took(1).errors(false).items(List.of(okItem)));
        VectorIngestionService working = new VectorIngestionService(null, chunkRepository, documentRepository,
                workspaceIdResolver, modelFactory, new SeuEsProperties("kb_chunk", 1024)) {
            @Override
            BulkResponse callBulk(BulkRequest request) {
                return ok;
            }
        };

        working.ingest(1L);

        assertEquals(ChunkStatus.INDEXED.value(), c.getStatus());
        assertEquals("1", c.getEsId());
        ArgumentCaptor<Document> captor = ArgumentCaptor.forClass(Document.class);
        verify(documentRepository).save(captor.capture());
        assertNull(captor.getValue().getErrorMsg(), "全部追平不应留下摘要");
    }

    // ===== 构造辅助 =====

    private Chunk chunk(long id, String title, String content) {
        Chunk c = new Chunk();
        c.setId(id);
        c.setDocId(1L);
        c.setKbId(1L);
        c.setTitle(title);
        c.setContent(content);
        c.setStatus(ChunkStatus.EMBEDDING.value());
        return c;
    }

    private Document doc() {
        Document d = new Document();
        d.setId(1L);
        d.setKbId(1L);
        d.setFileName("test.md");
        return d;
    }

    /** 提取 chunk 列表 id（供分块边界断言） */
    private static List<Long> ids(List<Chunk> chunks) {
        return chunks.stream().map(Chunk::getId).toList();
    }
}
