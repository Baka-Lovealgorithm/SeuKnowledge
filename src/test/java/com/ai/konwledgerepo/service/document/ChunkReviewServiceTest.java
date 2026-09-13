package com.ai.konwledgerepo.service.document;

import com.ai.konwledgerepo.common.BizException;
import com.ai.konwledgerepo.dto.ChunkReviewResponse;
import com.ai.konwledgerepo.entity.Chunk;
import com.ai.konwledgerepo.entity.ChunkReviewLog;
import com.ai.konwledgerepo.entity.ChunkStatus;
import com.ai.konwledgerepo.entity.Document;
import com.ai.konwledgerepo.repository.ChunkRepository;
import com.ai.konwledgerepo.repository.ChunkReviewLogRepository;
import com.ai.konwledgerepo.repository.DocumentRepository;
import com.ai.konwledgerepo.service.vector.VectorIngestionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 文档精修服务测试：SUSPECT 队列 / 保留（DEFER 后触发向量化）/ 编辑（内容+重索引+审计）/
 * 删除（FILTERED+ES 移除+审计）/ 已审核回退待审核（KEEP→SUSPECT+ES 移除）/ 批量 / 幂等与边界。
 * 批量审核逐项跑在 REQUIRES_NEW 事务里（见 ChunkReviewService#itemTxTemplate），
 * 测试以 mock 的 PlatformTransactionManager（getTransaction 返回空状态）模拟事务边界。
 */
class ChunkReviewServiceTest {

    private ChunkRepository chunkRepository;
    private ChunkReviewLogRepository reviewLogRepository;
    private DocumentRepository documentRepository;
    private VectorIngestionService vectorIngestionService;
    private ChunkReviewService service;

    @BeforeEach
    void setUp() {
        chunkRepository = mock(ChunkRepository.class);
        reviewLogRepository = mock(ChunkReviewLogRepository.class);
        documentRepository = mock(DocumentRepository.class);
        vectorIngestionService = mock(VectorIngestionService.class);
        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        when(txManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        service = new ChunkReviewService(chunkRepository, reviewLogRepository, documentRepository,
                vectorIngestionService, txManager);
        when(documentRepository.findById(1L)).thenReturn(Optional.of(doc(1L, "测试.pdf")));
        when(documentRepository.findAllById(any())).thenReturn(List.of(doc(1L, "测试.pdf")));
    }

    // ===== suspectQueue =====

    @Test
    void suspectQueue_returnsSuspectChunksWithDocName() {
        Chunk c = suspectChunk(10L, 1L, "3.2 标题", "可疑内容", "C3 跨 chunk 长重复");
        when(chunkRepository.findByKbIdAndCleanStatusOrderByDocIdAscSeqAsc(7L, "SUSPECT"))
                .thenReturn(List.of(c));

        List<ChunkReviewResponse> queue = service.suspectQueue(7L);

        assertEquals(1, queue.size());
        ChunkReviewResponse item = queue.get(0);
        assertEquals(10L, item.chunkId());
        assertEquals("测试.pdf", item.docName());
        assertEquals("C3 跨 chunk 长重复", item.cleanReason());
    }

    @Test
    void suspectQueue_empty_returnsEmptyList() {
        when(chunkRepository.findByKbIdAndCleanStatusOrderByDocIdAscSeqAsc(7L, "SUSPECT"))
                .thenReturn(List.of());
        assertTrue(service.suspectQueue(7L).isEmpty());
    }

    // ===== keep =====

    @Test
    void keep_suspectChunk_setsKeepAndReindexes() {
        Chunk c = suspectChunk(10L, 1L, null, "正文内容", "A4 超短碎片");
        when(chunkRepository.findById(10L)).thenReturn(Optional.of(c));

        ChunkReviewResponse resp = service.keep(10L, 99L);

        assertEquals("KEEP", c.getCleanStatus());
        verify(vectorIngestionService).reindexChunk(c);
        verify(reviewLogRepository).save(any(ChunkReviewLog.class));
        assertEquals(10L, resp.chunkId());
    }

    @Test
    void keep_nonSuspect_skipsIdempotently() {
        Chunk c = suspectChunk(10L, 1L, null, "正文", "C3");
        c.setCleanStatus("KEEP");
        when(chunkRepository.findById(10L)).thenReturn(Optional.of(c));

        service.keep(10L, 99L);

        verify(vectorIngestionService, never()).reindexChunk(any(Chunk.class));
        verify(reviewLogRepository, never()).save(any(ChunkReviewLog.class));
    }

    @Test
    void keep_missingChunk_throws() {
        when(chunkRepository.findById(10L)).thenReturn(Optional.empty());
        assertThrows(BizException.class, () -> service.keep(10L, 99L));
    }

    // ===== edit =====

    @Test
    void edit_updatesContentTitleAndReindexes() {
        Chunk c = suspectChunk(10L, 1L, "旧标题", "旧内容", "C3");
        when(chunkRepository.findById(10L)).thenReturn(Optional.of(c));

        ChunkReviewResponse resp = service.edit(10L, "新内容", "新标题", 99L);

        assertEquals("新内容", c.getContent());
        assertEquals("新标题", c.getTitle());
        assertEquals("KEEP", c.getCleanStatus());
        verify(vectorIngestionService).reindexChunk(c);
        ArgumentCaptor<ChunkReviewLog> captor = ArgumentCaptor.forClass(ChunkReviewLog.class);
        verify(reviewLogRepository).save(captor.capture());
        assertEquals("edit", captor.getValue().getAction());
        assertEquals("旧内容", captor.getValue().getBeforeContent());
        assertEquals("新内容", captor.getValue().getAfterContent());
        assertEquals(99L, captor.getValue().getUserId());
        assertEquals(10L, resp.chunkId());
    }

    @Test
    void edit_blankContent_rejected() {
        Chunk c = suspectChunk(10L, 1L, null, "旧内容", "C3");
        when(chunkRepository.findById(10L)).thenReturn(Optional.of(c));
        assertThrows(BizException.class, () -> service.edit(10L, "   ", null, 99L));
        verify(vectorIngestionService, never()).reindexChunk(any(Chunk.class));
    }

    @Test
    void edit_keepChunk_allowedAndReindexes() {
        Chunk c = suspectChunk(10L, 1L, null, "正文", "C3");
        c.setCleanStatus("KEEP");
        c.setEsId("es_10");
        when(chunkRepository.findById(10L)).thenReturn(Optional.of(c));

        ChunkReviewResponse resp = service.edit(10L, "新内容", null, 99L);

        assertEquals("新内容", c.getContent());
        assertEquals("KEEP", c.getCleanStatus());
        verify(vectorIngestionService).reindexChunk(c);
        assertEquals(10L, resp.chunkId());
    }

    @Test
    void edit_normalNullChunk_allowedAndReindexes() {
        Chunk c = suspectChunk(10L, 1L, null, "正文", "C3");
        c.setCleanStatus(null);
        when(chunkRepository.findById(10L)).thenReturn(Optional.of(c));

        service.edit(10L, "修订内容", "修订标题", 99L);

        assertEquals("修订内容", c.getContent());
        assertEquals("KEEP", c.getCleanStatus());
        verify(vectorIngestionService).reindexChunk(c);
    }

    @Test
    void edit_filteredChunk_rejected() {
        Chunk c = suspectChunk(10L, 1L, null, "正文", "C3");
        c.setCleanStatus("FILTERED");
        c.setStatus(ChunkStatus.FILTERED.value());
        when(chunkRepository.findById(10L)).thenReturn(Optional.of(c));
        assertThrows(BizException.class, () -> service.edit(10L, "新内容", null, 99L));
        verify(vectorIngestionService, never()).reindexChunk(any(Chunk.class));
    }

    // ===== drop =====

    @Test
    void drop_marksFilteredAndDeletesEs() {
        Chunk c = suspectChunk(10L, 1L, null, "要被删除的内容", "B1 孤立图题");
        when(chunkRepository.findById(10L)).thenReturn(Optional.of(c));

        ChunkReviewResponse resp = service.drop(10L, 99L);

        assertEquals("FILTERED", c.getCleanStatus());
        assertEquals(ChunkStatus.FILTERED.value(), c.getStatus());
        verify(vectorIngestionService).deleteByChunkId(10L);
        ArgumentCaptor<ChunkReviewLog> captor = ArgumentCaptor.forClass(ChunkReviewLog.class);
        verify(reviewLogRepository).save(captor.capture());
        assertEquals("drop", captor.getValue().getAction());
        assertEquals("要被删除的内容", captor.getValue().getBeforeContent());
        assertEquals(10L, resp.chunkId());
    }

    @Test
    void drop_nonSuspect_skipsIdempotently() {
        Chunk c = suspectChunk(10L, 1L, null, "正文", "C3");
        c.setCleanStatus("FILTERED");
        c.setStatus(ChunkStatus.FILTERED.value());
        when(chunkRepository.findById(10L)).thenReturn(Optional.of(c));

        service.drop(10L, 99L);

        verify(vectorIngestionService, never()).deleteByChunkId(anyLong());
    }

    // ===== createChunk（已向量化文档新增分块） =====

    @Test
    void createChunk_insertsAfterAnchorShiftsSeqAndReindexes() {
        Chunk anchor = suspectChunk(10L, 1L, "锚点标题", "锚点内容", null);
        anchor.setSeq(3);
        anchor.setCleanStatus("KEEP");
        anchor.setPageNum(5);
        when(chunkRepository.findById(10L)).thenReturn(Optional.of(anchor));

        ChunkReviewResponse resp = service.createChunk(1L, 10L, "新块内容", "新标题", 99L);

        verify(chunkRepository).shiftSeqFrom(1L, 4);
        ArgumentCaptor<Chunk> captor = ArgumentCaptor.forClass(Chunk.class);
        verify(chunkRepository).saveAndFlush(captor.capture());
        Chunk created = captor.getValue();
        assertEquals(4, created.getSeq());
        assertEquals("KEEP", created.getCleanStatus());
        assertEquals(ChunkStatus.EMBEDDING.value(), created.getStatus());
        assertEquals(5, created.getPageNum());
        verify(vectorIngestionService).reindexChunk(created);
        verify(reviewLogRepository).save(any(ChunkReviewLog.class));
        assertEquals(4, resp.seq());
        assertEquals("新块内容", resp.content());
    }

    @Test
    void createChunk_blankContent_rejected() {
        assertThrows(BizException.class, () -> service.createChunk(1L, 10L, "  ", null, 99L));
        verify(chunkRepository, never()).shiftSeqFrom(anyLong(), any());
    }

    @Test
    void createChunk_anchorOfOtherDoc_rejected() {
        Chunk anchor = suspectChunk(10L, 2L, null, "锚点", null);
        when(chunkRepository.findById(10L)).thenReturn(Optional.of(anchor));
        assertThrows(BizException.class, () -> service.createChunk(1L, 10L, "内容", null, 99L));
    }

    @Test
    void createChunk_curatingDoc_rejected() {
        when(documentRepository.findById(1L)).thenReturn(Optional.of(curatingDoc(1L)));
        assertThrows(BizException.class, () -> service.createChunk(1L, 10L, "内容", null, 99L));
        verify(chunkRepository, never()).shiftSeqFrom(anyLong(), any());
    }

    @Test
    void createChunk_reindexFails_chunkCountNotBumped() {
        Chunk anchor = suspectChunk(10L, 1L, null, "锚点", null);
        anchor.setCleanStatus("KEEP");
        anchor.setSeq(2);
        when(chunkRepository.findById(10L)).thenReturn(Optional.of(anchor));
        doThrow(new RuntimeException("embedding 失败"))
                .when(vectorIngestionService).reindexChunk(any(Chunk.class));

        assertThrows(RuntimeException.class, () -> service.createChunk(1L, 10L, "内容", null, 99L));
        verify(documentRepository, never()).save(any(Document.class));
    }

    // ===== mergeChunk（已向量化文档合并相邻分块） =====

    @Test
    void mergeChunk_adjacentIndexedChunks_mergesReindexesAndDropsSource() {
        Chunk source = suspectChunk(11L, 1L, null, "后块内容", null);
        source.setSeq(4);
        source.setCleanStatus(null);
        source.setEsId("es_11");
        source.setStatus(ChunkStatus.INDEXED.value());
        Chunk target = suspectChunk(10L, 1L, "目标标题", "前块内容", null);
        target.setSeq(3);
        target.setCleanStatus("KEEP");
        target.setEsId("es_10");
        target.setStatus(ChunkStatus.INDEXED.value());
        target.setPageNum(2);
        source.setPageNum(3);
        when(chunkRepository.findById(11L)).thenReturn(Optional.of(source));
        when(chunkRepository.findById(10L)).thenReturn(Optional.of(target));

        ChunkReviewResponse resp = service.mergeChunk(1L, 11L, 10L, 99L);

        assertEquals("前块内容\n后块内容", target.getContent());
        assertEquals("KEEP", target.getCleanStatus());
        assertEquals(2, target.getPageNum());
        assertEquals("FILTERED", source.getCleanStatus());
        assertEquals(ChunkStatus.FILTERED.value(), source.getStatus());
        verify(vectorIngestionService).reindexChunk(target);
        verify(vectorIngestionService).deleteByChunkId(11L);
        ArgumentCaptor<ChunkReviewLog> captor = ArgumentCaptor.forClass(ChunkReviewLog.class);
        verify(reviewLogRepository, org.mockito.Mockito.times(2)).save(captor.capture());
        assertEquals("merge", captor.getAllValues().get(0).getAction());
        assertEquals("merge-source", captor.getAllValues().get(1).getAction());
        assertEquals(10L, resp.chunkId());
    }

    @Test
    void mergeChunk_sourceFirstConcatenatesInSeqOrder() {
        Chunk source = suspectChunk(10L, 1L, null, "前块", null);
        source.setSeq(3);
        source.setCleanStatus(null);
        Chunk target = suspectChunk(11L, 1L, null, "后块", null);
        target.setSeq(4);
        target.setCleanStatus(null);
        when(chunkRepository.findById(10L)).thenReturn(Optional.of(source));
        when(chunkRepository.findById(11L)).thenReturn(Optional.of(target));

        service.mergeChunk(1L, 10L, 11L, 99L);

        assertEquals("前块\n后块", target.getContent());
    }

    @Test
    void mergeChunk_nonAdjacent_rejected() {
        Chunk source = suspectChunk(10L, 1L, null, "a", null);
        source.setSeq(3);
        source.setCleanStatus(null);
        Chunk target = suspectChunk(11L, 1L, null, "b", null);
        target.setSeq(5);
        target.setCleanStatus(null);
        when(chunkRepository.findById(10L)).thenReturn(Optional.of(source));
        when(chunkRepository.findById(11L)).thenReturn(Optional.of(target));

        assertThrows(BizException.class, () -> service.mergeChunk(1L, 10L, 11L, 99L));
        verify(vectorIngestionService, never()).reindexChunk(any(Chunk.class));
    }

    @Test
    void mergeChunk_sameChunk_rejected() {
        Chunk c = suspectChunk(10L, 1L, null, "a", null);
        c.setCleanStatus(null);
        when(chunkRepository.findById(10L)).thenReturn(Optional.of(c));
        assertThrows(BizException.class, () -> service.mergeChunk(1L, 10L, 10L, 99L));
    }

    @Test
    void mergeChunk_filteredInvolved_rejected() {
        Chunk source = suspectChunk(10L, 1L, null, "a", null);
        source.setSeq(3);
        source.setCleanStatus(null);
        Chunk target = suspectChunk(11L, 1L, null, "b", null);
        target.setSeq(4);
        target.setCleanStatus("FILTERED");
        when(chunkRepository.findById(10L)).thenReturn(Optional.of(source));
        when(chunkRepository.findById(11L)).thenReturn(Optional.of(target));

        assertThrows(BizException.class, () -> service.mergeChunk(1L, 10L, 11L, 99L));
    }

    @Test
    void mergeChunk_curatingDoc_rejected() {
        Chunk source = suspectChunk(10L, 1L, null, "a", null);
        source.setSeq(3);
        source.setCleanStatus(null);
        Chunk target = suspectChunk(11L, 1L, null, "b", null);
        target.setSeq(4);
        target.setCleanStatus(null);
        when(chunkRepository.findById(10L)).thenReturn(Optional.of(source));
        when(chunkRepository.findById(11L)).thenReturn(Optional.of(target));
        when(documentRepository.findById(1L)).thenReturn(Optional.of(curatingDoc(1L)));

        assertThrows(BizException.class, () -> service.mergeChunk(1L, 10L, 11L, 99L));
        verify(vectorIngestionService, never()).reindexChunk(any(Chunk.class));
    }

    // ===== unkeep（已审核回退待审核） =====

    @Test
    void unkeep_keepChunkWithEsId_marksSuspectAndDeletesEs() {
        Chunk c = suspectChunk(10L, 1L, null, "正文", "C3");
        c.setCleanStatus("KEEP");
        c.setEsId("es_10");
        when(chunkRepository.findById(10L)).thenReturn(Optional.of(c));

        ChunkReviewResponse resp = service.unkeep(10L, 99L);

        assertEquals("SUSPECT", c.getCleanStatus());
        verify(vectorIngestionService).deleteByChunkId(10L);
        verify(reviewLogRepository).save(any(ChunkReviewLog.class));
        assertEquals(10L, resp.chunkId());
    }

    @Test
    void unkeep_keepChunkWithoutEsId_skipsEsDelete() {
        Chunk c = suspectChunk(10L, 1L, null, "正文", "C3");
        c.setCleanStatus("KEEP");
        when(chunkRepository.findById(10L)).thenReturn(Optional.of(c));

        service.unkeep(10L, 99L);

        assertEquals("SUSPECT", c.getCleanStatus());
        verify(vectorIngestionService, never()).deleteByChunkId(anyLong());
    }

    @Test
    void unkeep_normalNullCleanStatus_marksSuspect() {
        Chunk c = suspectChunk(10L, 1L, null, "正文", "C3");
        c.setCleanStatus(null);
        when(chunkRepository.findById(10L)).thenReturn(Optional.of(c));

        service.unkeep(10L, 99L);

        assertEquals("SUSPECT", c.getCleanStatus());
        verify(vectorIngestionService, never()).deleteByChunkId(anyLong());
    }

    @Test
    void unkeep_nonKeep_rejected() {
        Chunk c = suspectChunk(10L, 1L, null, "正文", "C3");
        c.setCleanStatus("SUSPECT");
        when(chunkRepository.findById(10L)).thenReturn(Optional.of(c));

        assertThrows(BizException.class, () -> service.unkeep(10L, 99L));
    }

    @Test
    void unkeep_curatingDoc_rejected() {
        Chunk c = suspectChunk(10L, 1L, null, "正文", "C3");
        c.setCleanStatus("KEEP");
        when(chunkRepository.findById(10L)).thenReturn(Optional.of(c));
        when(documentRepository.findById(1L)).thenReturn(Optional.of(curatingDoc(1L)));

        assertThrows(BizException.class, () -> service.unkeep(10L, 99L));
    }

    // ===== batch =====

    @Test
    void batch_keepAndDrop_mixedResults() {
        Chunk keep = suspectChunk(10L, 1L, null, "保留内容", "C3");
        Chunk drop = suspectChunk(11L, 1L, null, "删除内容", "B2");
        when(chunkRepository.findById(10L)).thenReturn(Optional.of(keep));
        when(chunkRepository.findById(11L)).thenReturn(Optional.of(drop));

        List<ChunkReviewService.BatchItemResult> results = service.batch(List.of(10L, 11L), "keep", 99L);

        assertEquals(2, results.size());
        assertTrue(results.get(0).success());
        verify(vectorIngestionService).reindexChunk(keep);
        // drop 项在 keep 批量里按 keep 处理（batch 语义为同动作批量）
        verify(vectorIngestionService).reindexChunk(drop);
    }

    @Test
    void batch_partialFailure_reportsPerItem() {
        Chunk ok = suspectChunk(10L, 1L, null, "内容", "C3");
        when(chunkRepository.findById(10L)).thenReturn(Optional.of(ok));
        when(chunkRepository.findById(11L)).thenReturn(Optional.empty());

        List<ChunkReviewService.BatchItemResult> results = service.batch(List.of(10L, 11L), "keep", 99L);

        assertEquals(2, results.size());
        assertTrue(results.get(0).success());
        assertFalse(results.get(1).success());
        verify(vectorIngestionService).reindexChunk(ok);
    }

    @Test
    void batch_invalidAction_throws() {
        assertThrows(BizException.class, () -> service.batch(List.of(1L), "rename", 99L));
    }

    /**
     * 回归锁（批量审核失败隔离）：批量审核每项必须跑在独立事务里。
     * 此前共用一个事务时，某项向量化失败被 batch 捕获后「对外报失败」，
     * 但该项已把 clean_status 改成 KEEP 的脏改会随外层提交落库——
     * DB=KEEP 而 ES 无向量，分块从待审核队列静默消失（内容丢失且不可恢复）。
     * 改为 REQUIRES_NEW 后失败项整体回滚，且失败项不污染后续项的处理。
     */
    @Test
    void batch_reindexFails_reportedFailedAndDoesNotBlockLaterItems() {
        Chunk failing = suspectChunk(10L, 1L, null, "会失败的内容", "C3");
        Chunk following = suspectChunk(11L, 1L, null, "后续内容", "B2");
        when(chunkRepository.findById(10L)).thenReturn(Optional.of(failing));
        when(chunkRepository.findById(11L)).thenReturn(Optional.of(following));
        doThrow(new RuntimeException("embedding 服务不可用"))
                .when(vectorIngestionService).reindexChunk(failing);

        List<ChunkReviewService.BatchItemResult> results = service.batch(List.of(10L, 11L), "keep", 99L);

        assertEquals(2, results.size());
        assertFalse(results.get(0).success(), "向量化失败项必须对外报失败");
        assertTrue(results.get(0).reason().contains("embedding"));
        assertTrue(results.get(1).success(), "失败项不得影响后续项处理");
        verify(vectorIngestionService).reindexChunk(following);
    }

    @Test
    void batch_dropFailure_reportedFailedButItemMarked() {
        Chunk failing = suspectChunk(10L, 1L, null, "内容", "B1");
        when(chunkRepository.findById(10L)).thenReturn(Optional.of(failing));
        doThrow(new RuntimeException("ES 删除失败"))
                .when(vectorIngestionService).deleteByChunkId(10L);

        List<ChunkReviewService.BatchItemResult> results = service.batch(List.of(10L), "drop", 99L);

        assertEquals(1, results.size());
        assertFalse(results.get(0).success());
    }

    // ===== 构造辅助 =====

    private Chunk suspectChunk(long id, long docId, String title, String content, String reason) {
        Chunk c = new Chunk();
        c.setId(id);
        c.setDocId(docId);
        c.setKbId(7L);
        c.setSeq(1);
        c.setTitle(title);
        c.setContent(content);
        c.setStatus(ChunkStatus.EMBEDDING.value());
        c.setCleanStatus("SUSPECT");
        c.setCleanReason(reason);
        return c;
    }

    private Document doc(long id, String name) {
        Document d = new Document();
        d.setId(id);
        d.setKbId(7L);
        d.setFileName(name);
        d.setParseStatus(com.ai.konwledgerepo.entity.DocStatus.SUCCESS.value());
        return d;
    }

    /** 处于精修（ACCEPTED）流程中的文档：常规精修接口应拒绝 */
    private Document curatingDoc(long id) {
        Document d = doc(id, "策展中.pdf");
        d.setCurateRequired(true);
        d.setCurateStatus(Document.CURATE_ACCEPTED);
        return d;
    }
}
