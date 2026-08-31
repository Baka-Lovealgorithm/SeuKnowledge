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
 * 覆盖：锁占用早退、startParse 失败、成功路径、异常路径。
 */
class DocumentParseExecutorTest {

    private TaskLock taskLock;
    private DocumentParseTx parseTx;
    private DocumentParserService parserService;
    private VectorIngestionService vectorIngestionService;
    private DocumentParseExecutor executor;

    private static final long DOC_ID = 1L;

    @BeforeEach
    void setUp() {
        taskLock = mock(TaskLock.class);
        parseTx = mock(DocumentParseTx.class);
        parserService = mock(DocumentParserService.class);
        vectorIngestionService = mock(VectorIngestionService.class);
        executor = new DocumentParseExecutor(taskLock, parseTx, parserService, vectorIngestionService);

        when(taskLock.tryAcquire(any(), any())).thenReturn(true);
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
        when(parseTx.finalizeSuccess(DOC_ID, pieces)).thenReturn(true);

        executor.parseAsync(DOC_ID, false);

        verify(parseTx).finalizeSuccess(DOC_ID, pieces);
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
        when(parseTx.finalizeSuccess(DOC_ID, pieces)).thenReturn(false);

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
}