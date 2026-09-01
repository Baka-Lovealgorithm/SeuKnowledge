package com.ai.konwledgerepo.service.document;

import com.ai.konwledgerepo.common.AfterCommitExecutor;
import com.ai.konwledgerepo.common.BizException;
import com.ai.konwledgerepo.dto.ChunkReviewResponse;
import com.ai.konwledgerepo.entity.Chunk;
import com.ai.konwledgerepo.entity.ChunkStatus;
import com.ai.konwledgerepo.entity.Document;
import com.ai.konwledgerepo.entity.DocumentCurate;
import com.ai.konwledgerepo.entity.DocumentCurateLog;
import com.ai.konwledgerepo.repository.ChunkRepository;
import com.ai.konwledgerepo.repository.ChunkReviewLogRepository;
import com.ai.konwledgerepo.repository.DocumentCurateLogRepository;
import com.ai.konwledgerepo.repository.DocumentCurateRepository;
import com.ai.konwledgerepo.repository.DocumentRepository;
import com.ai.konwledgerepo.service.vector.VectorIngestionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
 * 文档人工策展服务测试：md 切页 / 初始落库 / 保存 md 重分块 / 接受与确认状态流转 /
 * chunk 精修（编辑/删除/保留，确认前不触 ES）/ 边界与审计。
 */
class DocumentCurateServiceTest {

    private DocumentRepository documentRepository;
    private ChunkRepository chunkRepository;
    private DocumentCurateRepository curateRepository;
    private DocumentCurateLogRepository curateLogRepository;
    private ChunkReviewLogRepository reviewLogRepository;
    private DocumentParserService parserService;
    private DocumentCleanService documentCleanService;
    private DocumentParseTx parseTx;
    private VectorIngestionService vectorIngestionService;
    private AfterCommitExecutor afterCommitExecutor;
    private DocumentCurateService service;

    @BeforeEach
    void setUp() {
        documentRepository = mock(DocumentRepository.class);
        chunkRepository = mock(ChunkRepository.class);
        curateRepository = mock(DocumentCurateRepository.class);
        curateLogRepository = mock(DocumentCurateLogRepository.class);
        reviewLogRepository = mock(ChunkReviewLogRepository.class);
        parserService = mock(DocumentParserService.class);
        documentCleanService = mock(DocumentCleanService.class);
        parseTx = mock(DocumentParseTx.class);
        vectorIngestionService = mock(VectorIngestionService.class);
        afterCommitExecutor = mock(AfterCommitExecutor.class);
        service = new DocumentCurateService(documentRepository, chunkRepository, curateRepository,
                curateLogRepository, reviewLogRepository, parserService, documentCleanService, parseTx,
                vectorIngestionService, afterCommitExecutor);
    }

    private Document doc(Long id, String status) {
        Document d = new Document();
        d.setId(id);
        d.setKbId(7L);
        d.setFileName("排障指南.pdf");
        d.setCurateRequired(true);
        d.setCurateStatus(status);
        d.setChunkCount(3);
        return d;
    }

    private Chunk chunk(Long id, Long docId, String cleanStatus) {
        Chunk c = new Chunk();
        c.setId(id);
        c.setDocId(docId);
        c.setKbId(7L);
        c.setSeq(1);
        c.setPageNum(1);
        c.setContent("内容 " + id);
        c.setStatus(ChunkStatus.EMBEDDING.value());
        c.setCleanStatus(cleanStatus);
        return c;
    }

    // ===== splitPages（纯静态规则） =====

    @Test
    void splitPages_withMarks_parsesPagesAndPrefixIntoFirstPage() {
        String md = "开头前缀\n<!-- PAGE 1 -->\n第一页内容\n<!-- PAGE 2 -->\n第二页内容\n尾段";
        List<DocumentCurateService.PageEntry> pages = DocumentCurateService.splitPages(md);
        assertEquals(2, pages.size());
        assertEquals(1, pages.get(0).pageNum());
        assertTrue(pages.get(0).content().startsWith("开头前缀\n第一页内容"));
        assertEquals(2, pages.get(1).pageNum());
        assertTrue(pages.get(1).content().endsWith("第二页内容\n尾段"));
    }

    @Test
    void splitPages_withoutMarks_wholeTextGoesToPage1() {
        List<DocumentCurateService.PageEntry> pages = DocumentCurateService.splitPages("没有页标记的全文");
        assertEquals(1, pages.size());
        assertEquals(1, pages.get(0).pageNum());
        assertEquals("没有页标记的全文", pages.get(0).content());
    }

    @Test
    void splitPages_deletedMarkMergesIntoSamePage() {
        // 模拟用户删掉 PAGE 2 标记：只剩 PAGE 1，全部归第 1 页
        String md = "<!-- PAGE 1 -->\n第一页\n跨页移动的内容\n";
        List<DocumentCurateService.PageEntry> pages = DocumentCurateService.splitPages(md);
        assertEquals(1, pages.size());
        assertEquals(1, pages.get(0).pageNum());
        assertTrue(pages.get(0).content().contains("跨页移动的内容"));
    }

    // ===== docInfo =====

    @Test
    void docInfo_returnsStatusAndSuspectCount() {
        when(documentRepository.findById(1L)).thenReturn(Optional.of(doc(1L, Document.CURATE_PREVIEWING)));
        when(chunkRepository.countByDocIdAndCleanStatus(1L, "SUSPECT")).thenReturn(5L);

        DocumentCurateService.CurateDocInfo info = service.docInfo(1L);

        assertEquals(Document.CURATE_PREVIEWING, info.curateStatus());
        assertEquals(5L, info.suspectCount());
    }

    @Test
    void docInfo_missingDoc_throws() {
        when(documentRepository.findById(1L)).thenReturn(Optional.empty());
        assertThrows(BizException.class, () -> service.docInfo(1L));
    }

    // ===== saveMd =====

    @Test
    void saveMd_inPreviewing_savesVersionAndRechunks() {
        Document d = doc(1L, Document.CURATE_PREVIEWING);
        when(documentRepository.findById(1L)).thenReturn(Optional.of(d));
        when(curateRepository.maxVersion(1L)).thenReturn(1);
        when(documentCleanService.cleanPages(any())).thenReturn(
                new DocumentCleanService.PageCleanResult(
                        List.of(new LlamaParseService.PageMarkdown(1, "第一页内容")), List.of()));
        when(parserService.chunkFromPages(any())).thenReturn(
                List.of(new ChunkPiece("第一页内容", 1, "标题")));
        when(documentCleanService.cleanChunks(any())).thenReturn(
                new DocumentCleanService.ChunkCleanResult(
                        List.of(new ChunkPiece("第一页内容", 1, "标题")),
                        List.of()));
        when(parseTx.finalizeRechunk(anyLong(), anyList(), anyList())).thenReturn(true);

        DocumentCurateService.RechunkResult r = service.saveMd(1L, "<!-- PAGE 1 -->\n第一页内容", 9L);

        assertEquals(2, r.version());
        assertEquals(1, r.chunkCount());
        // 新版本落库
        ArgumentCaptor<List<DocumentCurate>> rows = ArgumentCaptor.forClass(List.class);
        verify(curateRepository).saveAll(rows.capture());
        assertEquals(1, rows.getValue().size());
        assertEquals(2, rows.getValue().get(0).getVersion());
        // 重分块
        verify(parseTx).finalizeRechunk(anyLong(), anyList(), anyList());
        // 审计
        verify(curateLogRepository).save(any(DocumentCurateLog.class));
    }

    @Test
    void saveMd_notPreviewing_rejected() {
        when(documentRepository.findById(1L)).thenReturn(Optional.of(doc(1L, Document.CURATE_ACCEPTED)));
        assertThrows(BizException.class, () -> service.saveMd(1L, "<!-- PAGE 1 -->\nx", 9L));
    }

    @Test
    void saveMd_rechunkFails_throws() {
        when(documentRepository.findById(1L)).thenReturn(Optional.of(doc(1L, Document.CURATE_PREVIEWING)));
        when(curateRepository.maxVersion(1L)).thenReturn(0);
        when(documentCleanService.cleanPages(any())).thenReturn(
                new DocumentCleanService.PageCleanResult(List.of(new LlamaParseService.PageMarkdown(1, "x")), List.of()));
        when(parserService.chunkFromPages(any())).thenReturn(List.of(new ChunkPiece("x", 1, null)));
        when(documentCleanService.cleanChunks(any())).thenReturn(
                new DocumentCleanService.ChunkCleanResult(List.of(), List.of()));
        when(parseTx.finalizeRechunk(anyLong(), anyList(), anyList())).thenReturn(false);

        assertThrows(BizException.class, () -> service.saveMd(1L, "<!-- PAGE 1 -->\nx", 9L));
    }

    // ===== accept =====

    @Test
    void accept_fromPreviewing_setsAccepted() {
        when(documentRepository.findById(1L)).thenReturn(Optional.of(doc(1L, Document.CURATE_PREVIEWING)));
        service.accept(1L, 9L);
        ArgumentCaptor<Document> saved = ArgumentCaptor.forClass(Document.class);
        verify(documentRepository).save(saved.capture());
        assertEquals(Document.CURATE_ACCEPTED, saved.getValue().getCurateStatus());
        verify(curateLogRepository).save(any(DocumentCurateLog.class));
    }

    @Test
    void accept_notPreviewing_rejected() {
        when(documentRepository.findById(1L)).thenReturn(Optional.of(doc(1L, Document.CURATE_ACCEPTED)));
        assertThrows(BizException.class, () -> service.accept(1L, 9L));
    }

    // ===== confirm =====

    @Test
    void confirm_fromAccepted_clearsStatusAndTriggersIngest() {
        when(documentRepository.findById(1L)).thenReturn(Optional.of(doc(1L, Document.CURATE_ACCEPTED)));
        service.confirm(1L, 9L);
        // 状态清空（save 的实体 curateStatus=null）——通过 ArgumentCaptor 校验
        ArgumentCaptor<Document> saved = ArgumentCaptor.forClass(Document.class);
        verify(documentRepository).save(saved.capture());
        assertNull(saved.getValue().getCurateStatus());
        // 触发异步 ingest
        verify(afterCommitExecutor).runAfterCommit(any(Runnable.class));
        verify(curateLogRepository).save(any(DocumentCurateLog.class));
    }

    @Test
    void confirm_notAccepted_rejected() {
        when(documentRepository.findById(1L)).thenReturn(Optional.of(doc(1L, Document.CURATE_PREVIEWING)));
        assertThrows(BizException.class, () -> service.confirm(1L, 9L));
    }

    // ===== chunk 精修（仅 ACCEPTED，确认前不触 ES） =====

    @Test
    void editChunk_accepted_updatesMysqlWithoutEs() {
        when(documentRepository.findById(1L)).thenReturn(Optional.of(doc(1L, Document.CURATE_ACCEPTED)));
        Chunk c = chunk(10L, 1L, "KEEP");
        when(chunkRepository.findById(10L)).thenReturn(Optional.of(c));

        ChunkReviewResponse r = service.editChunk(10L, "新内容", "新标题", 9L);

        assertEquals("新内容", r.content());
        assertEquals("新标题", r.title());
        verify(chunkRepository).save(c);
        verify(vectorIngestionService, never()).reindexChunk(any());
        verify(reviewLogRepository).save(any());
    }

    @Test
    void editChunk_notAccepted_rejected() {
        when(documentRepository.findById(1L)).thenReturn(Optional.of(doc(1L, Document.CURATE_PREVIEWING)));
        when(chunkRepository.findById(10L)).thenReturn(Optional.of(chunk(10L, 1L, "KEEP")));
        assertThrows(BizException.class, () -> service.editChunk(10L, "x", null, 9L));
    }

    @Test
    void editChunk_blankContent_rejected() {
        when(documentRepository.findById(1L)).thenReturn(Optional.of(doc(1L, Document.CURATE_ACCEPTED)));
        when(chunkRepository.findById(10L)).thenReturn(Optional.of(chunk(10L, 1L, "KEEP")));
        assertThrows(BizException.class, () -> service.editChunk(10L, "   ", null, 9L));
    }

    @Test
    void dropChunk_accepted_marksFilteredWithoutEsDelete() {
        when(documentRepository.findById(1L)).thenReturn(Optional.of(doc(1L, Document.CURATE_ACCEPTED)));
        Chunk c = chunk(10L, 1L, "KEEP");
        when(chunkRepository.findById(10L)).thenReturn(Optional.of(c));

        service.dropChunk(10L, 9L);

        assertEquals("FILTERED", c.getCleanStatus());
        assertEquals(ChunkStatus.FILTERED.value(), c.getStatus());
        verify(vectorIngestionService, never()).deleteByChunkId(anyLong());
    }

    @Test
    void keepChunk_suspectMarksKeep() {
        when(documentRepository.findById(1L)).thenReturn(Optional.of(doc(1L, Document.CURATE_ACCEPTED)));
        Chunk c = chunk(10L, 1L, "SUSPECT");
        when(chunkRepository.findById(10L)).thenReturn(Optional.of(c));

        service.keepChunk(10L, 9L);

        assertEquals("KEEP", c.getCleanStatus());
        verify(vectorIngestionService, never()).reindexChunk(any());
    }

    @Test
    void keepChunk_nonSuspect_idempotent() {
        when(documentRepository.findById(1L)).thenReturn(Optional.of(doc(1L, Document.CURATE_ACCEPTED)));
        Chunk c = chunk(10L, 1L, "KEEP");
        when(chunkRepository.findById(10L)).thenReturn(Optional.of(c));

        service.keepChunk(10L, 9L);

        verify(chunkRepository, never()).save(any());
    }

    // ===== getMd =====

    @Test
    void getMd_reassemblesPagesWithMarkers() {
        when(documentRepository.findById(1L)).thenReturn(Optional.of(doc(1L, Document.CURATE_PREVIEWING)));
        DocumentCurate p1 = new DocumentCurate();
        p1.setDocId(1L);
        p1.setVersion(2);
        p1.setPageNum(1);
        p1.setContent("第一页");
        DocumentCurate p2 = new DocumentCurate();
        p2.setDocId(1L);
        p2.setVersion(2);
        p2.setPageNum(2);
        p2.setContent("第二页");
        when(curateRepository.findByDocIdOrderByVersionDescPageNumAsc(1L)).thenReturn(List.of(p2, p1));

        String md = service.getMd(1L);

        assertTrue(md.contains("<!-- PAGE 1 -->"));
        assertTrue(md.contains("第一页"));
        assertTrue(md.contains("<!-- PAGE 2 -->"));
        assertTrue(md.contains("第二页"));
    }

    @Test
    void getMd_noCurateMd_throws() {
        when(documentRepository.findById(1L)).thenReturn(Optional.of(doc(1L, Document.CURATE_PREVIEWING)));
        when(curateRepository.findByDocIdOrderByVersionDescPageNumAsc(1L)).thenReturn(List.of());
        assertThrows(BizException.class, () -> service.getMd(1L));
    }

    // ===== queue =====

    @Test
    void queue_filtersCuratingDocs() {
        when(documentRepository.findByKbIdAndCurateStatusInOrderByIdDesc(anyLong(), any()))
                .thenReturn(List.of(doc(1L, Document.CURATE_PREVIEWING), doc(2L, Document.CURATE_ACCEPTED)));
        when(chunkRepository.countByDocIdAndCleanStatus(anyLong(), anyString())).thenReturn(2L);

        List<DocumentCurateService.CurateQueueItem> queue = service.queue(7L);

        assertEquals(2, queue.size());
        assertEquals(Document.CURATE_ACCEPTED, queue.get(1).curateStatus());
        assertEquals(2L, queue.get(0).suspectCount());
    }

    @Test
    void queue_empty_returnsEmpty() {
        when(documentRepository.findByKbIdAndCurateStatusInOrderByIdDesc(anyLong(), any()))
                .thenReturn(List.of());
        assertTrue(service.queue(7L).isEmpty());
    }
}
