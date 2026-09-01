package com.ai.konwledgerepo.service.document;

import com.ai.konwledgerepo.common.RedisKeys;
import com.ai.konwledgerepo.common.TaskLock;
import com.ai.konwledgerepo.entity.Document;
import com.ai.konwledgerepo.service.vector.VectorIngestionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DocumentParseExecutor 单元测试：mock DocumentParserService（不实例化真实解析器）。
 * 覆盖：锁占用早退、startParse 失败、成功路径、异常路径、策展门分支（pdf 走门不向量化）。
 */
class DocumentParseExecutorTest {

    private TaskLock taskLock;
    private DocumentParseTx parseTx;
    private DocumentParserService parserService;
    private DocumentCleanService documentCleanService;
    private DocumentCurateService curateService;
    private VectorIngestionService vectorIngestionService;
    private DocumentParseExecutor executor;

    private static final long DOC_ID = 1L;

    @BeforeEach
    void setUp() {
        taskLock = mock(TaskLock.class);
        parseTx = mock(DocumentParseTx.class);
        parserService = mock(DocumentParserService.class);
        documentCleanService = mock(DocumentCleanService.class);
        curateService = mock(DocumentCurateService.class);
        vectorIngestionService = mock(VectorIngestionService.class);
        executor = new DocumentParseExecutor(taskLock, parseTx, parserService, documentCleanService, curateService,
                vectorIngestionService);

        when(taskLock.tryAcquire(any(), any())).thenReturn(true);
        // 清洗默认透传：原 pieces 全部保留、无清洗判定
        when(documentCleanService.cleanChunks(any())).thenAnswer(inv -> {
            List<ChunkPiece> pieces = inv.getArgument(0);
            return new DocumentCleanService.ChunkCleanResult(pieces, List.of());
        });
    }

    @Test
    void parseAsync_lockBusy_returnsEarly() {
        when(taskLock.tryAcquire(eq(RedisKeys.docParse(DOC_ID)), any())).thenReturn(false);

        executor.parseAsync(DOC_ID, false);

        verify(parseTx, never()).startParse(any());
        verify(parseTx, never()).finalizeSuccess(any(), any());
        verify(parseTx, never()).finalizeFailure(any(), any());
    }

    @Test
    void parseAsync_startParseFails_returnsEarly() {
        when(parseTx.startParse(DOC_ID)).thenReturn(Optional.empty());

        executor.parseAsync(DOC_ID, false);

        verify(parseTx, never()).finalizeSuccess(any(), any());
        verify(parseTx, never()).finalizeFailure(any(), any());
        verify(parserService, never()).parse(any());
        verify(taskLock).release(RedisKeys.docParse(DOC_ID));
    }

    @Test
    void parseAsync_success_finalizesAndIngests() {
        Document doc = new Document();
        doc.setId(DOC_ID);
        doc.setKbId(10L);
        doc.setFileName("test.pdf");
        when(parseTx.startParse(DOC_ID)).thenReturn(Optional.of(doc));
        List<ChunkPiece> pieces = List.of(new ChunkPiece("content", 1, "title"));
        when(parserService.parse(doc)).thenReturn(pieces);
        when(parseTx.finalizeSuccess(DOC_ID, pieces, List.of())).thenReturn(true);

        executor.parseAsync(DOC_ID, false);

        verify(parseTx).finalizeSuccess(DOC_ID, pieces, List.of());
        verify(vectorIngestionService).ingest(DOC_ID);
        verify(parseTx, never()).finalizeFailure(any(), any());
        verify(taskLock).release(RedisKeys.docParse(DOC_ID));
    }

    @Test
    void parseAsync_finalizeSuccess_returnsFalse_skipsIngest() {
        Document doc = new Document();
        doc.setId(DOC_ID);
        when(parseTx.startParse(DOC_ID)).thenReturn(Optional.of(doc));
        List<ChunkPiece> pieces = List.of(new ChunkPiece("content", 1, "title"));
        when(parserService.parse(doc)).thenReturn(pieces);
        when(parseTx.finalizeSuccess(DOC_ID, pieces, List.of())).thenReturn(false);

        executor.parseAsync(DOC_ID, false);

        verify(vectorIngestionService, never()).ingest(any());
        verify(taskLock).release(RedisKeys.docParse(DOC_ID));
    }

    @Test
    void parseAsync_parseThrows_finalizesFailure() {
        Document doc = new Document();
        doc.setId(DOC_ID);
        when(parseTx.startParse(DOC_ID)).thenReturn(Optional.of(doc));
        when(parserService.parse(doc)).thenThrow(new RuntimeException("parse error"));

        executor.parseAsync(DOC_ID, false);

        verify(parseTx).finalizeFailure(eq(DOC_ID), any());
        verify(parseTx, never()).finalizeSuccess(any(), any());
        verify(taskLock).release(RedisKeys.docParse(DOC_ID));
    }

    // ===== 策展门分支 =====

    @Test
    void parseAsync_curateRequiredPdf_goesGated_noIngest() {
        Document doc = new Document();
        doc.setId(DOC_ID);
        doc.setKbId(10L);
        doc.setFileName("test.pdf");
        doc.setFileType("pdf");
        doc.setCurateRequired(true);
        when(parseTx.startParse(DOC_ID)).thenReturn(Optional.of(doc));
        List<LlamaParseService.PageMarkdown> pages = List.of(new LlamaParseService.PageMarkdown(1, "md内容"));
        when(parserService.parseToPages(doc)).thenReturn(pages);
        List<ChunkPiece> pieces = List.of(new ChunkPiece("md内容", 1, "title"));
        when(parserService.chunkFromPages(pages)).thenReturn(pieces);
        when(parseTx.finalizeSuccessGated(DOC_ID, pieces, List.of())).thenReturn(true);

        executor.parseAsync(DOC_ID, false);

        verify(curateService).saveInitialMd(DOC_ID, pages, null);
        verify(parseTx).finalizeSuccessGated(DOC_ID, pieces, List.of());
        verify(vectorIngestionService, never()).ingest(any());
        verify(parserService, never()).parse(any());
        verify(taskLock).release(RedisKeys.docParse(DOC_ID));
    }

    @Test
    void parseAsync_curateRequiredTxt_fallsBackToAuto() {
        Document doc = new Document();
        doc.setId(DOC_ID);
        doc.setKbId(10L);
        doc.setFileName("test.txt");
        doc.setFileType("txt");
        doc.setCurateRequired(true);
        when(parseTx.startParse(DOC_ID)).thenReturn(Optional.of(doc));
        List<ChunkPiece> pieces = List.of(new ChunkPiece("content", 0, null));
        when(parserService.parse(doc)).thenReturn(pieces);
        when(parseTx.finalizeSuccess(DOC_ID, pieces, List.of())).thenReturn(true);

        executor.parseAsync(DOC_ID, false);

        verify(parserService).parse(doc);
        verify(parserService, never()).parseToPages(any());
        verify(vectorIngestionService).ingest(DOC_ID);
    }

    @Test
    void parseAsync_curateRequiredPdf_parseFails_finalizesFailure() {
        Document doc = new Document();
        doc.setId(DOC_ID);
        doc.setFileType("pdf");
        doc.setCurateRequired(true);
        when(parseTx.startParse(DOC_ID)).thenReturn(Optional.of(doc));
        when(parserService.parseToPages(doc)).thenThrow(new RuntimeException("llama parse error"));

        executor.parseAsync(DOC_ID, false);

        verify(parseTx).finalizeFailure(eq(DOC_ID), any());
        verify(curateService, never()).saveInitialMd(any(), any(), any());
        verify(taskLock).release(RedisKeys.docParse(DOC_ID));
    }
}