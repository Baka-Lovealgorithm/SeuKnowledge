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

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 清洗人工审核服务测试：SUSPECT 队列 / 保留（DEFER 后触发向量化）/ 编辑（内容+重索引+审计）/
 * 删除（FILTERED+ES 移除+审计）/ 批量 / 幂等与边界。
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
        service = new ChunkReviewService(chunkRepository, reviewLogRepository, documentRepository,
                vectorIngestionService);
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
    void edit_nonSuspect_rejected() {
        Chunk c = suspectChunk(10L, 1L, null, "正文", "C3");
        c.setCleanStatus("KEEP");
        when(chunkRepository.findById(10L)).thenReturn(Optional.of(c));
        assertThrows(BizException.class, () -> service.edit(10L, "新内容", null, 99L));
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
        return d;
    }
}
