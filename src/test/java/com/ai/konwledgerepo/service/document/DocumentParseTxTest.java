package com.ai.konwledgerepo.service.document;

import com.ai.konwledgerepo.entity.Chunk;
import com.ai.konwledgerepo.entity.DocStatus;
import com.ai.konwledgerepo.entity.Document;
import com.ai.konwledgerepo.repository.ChunkRepository;
import com.ai.konwledgerepo.repository.DocumentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DocumentParseTx 单元测试：直接调用（@Transactional 不生效，逻辑可测）。
 */
class DocumentParseTxTest {

    private DocumentRepository documentRepository;
    private ChunkRepository chunkRepository;
    private DocumentParseTx parseTx;

    @BeforeEach
    void setUp() {
        documentRepository = mock(DocumentRepository.class);
        chunkRepository = mock(ChunkRepository.class);
        parseTx = new DocumentParseTx(documentRepository, chunkRepository);
    }

    private static Document doc(long id, String status) {
        Document d = new Document();
        d.setId(id);
        d.setKbId(10L);
        d.setParseStatus(status);
        return d;
    }

    @Test
    void startParse_setsParsing_whenDocPending() {
        Document d = doc(1L, DocStatus.PENDING.value());
        when(documentRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(d));

        Optional<Document> result = parseTx.startParse(1L);
        assertTrue(result.isPresent());
        assertEquals(DocStatus.PARSING.value(), d.getParseStatus());
        verify(documentRepository).save(d);
    }

    @Test
    void startParse_returnsEmpty_whenDocNotFound() {
        when(documentRepository.findByIdForUpdate(1L)).thenReturn(Optional.empty());
        assertTrue(parseTx.startParse(1L).isEmpty());
        verify(documentRepository, never()).save(any());
    }

    @Test
    void startParse_returnsEmpty_whenDocNotPending() {
        Document d = doc(1L, DocStatus.SUCCESS.value());
        when(documentRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(d));
        assertTrue(parseTx.startParse(1L).isEmpty());
        verify(documentRepository, never()).save(any());
    }

    @Test
    void finalizeSuccess_savesChunksAndSetsSuccess() {
        Document d = doc(1L, DocStatus.PARSING.value());
        when(documentRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(d));
        when(chunkRepository.saveAll(anyList())).thenReturn(List.of());

        ChunkPiece piece = new ChunkPiece("text", 1, "title");
        boolean ok = parseTx.finalizeSuccess(1L, List.of(piece));

        assertTrue(ok);
        assertEquals(DocStatus.SUCCESS.value(), d.getParseStatus());
        assertEquals(1, d.getChunkCount());
        verify(chunkRepository).saveAll(anyList());
        verify(documentRepository).save(d);
    }

    @Test
    void finalizeSuccess_returnsFalse_whenDocNotFound() {
        when(documentRepository.findByIdForUpdate(1L)).thenReturn(Optional.empty());

        boolean ok = parseTx.finalizeSuccess(1L, List.of(new ChunkPiece("text", 1, "title")));
        assertFalse(ok);
        verify(chunkRepository, never()).saveAll(anyList());
        verify(documentRepository, never()).save(any());
    }

    @Test
    void finalizeFailure_setsFailed_whenDocExists() {
        Document d = doc(1L, DocStatus.PARSING.value());
        when(documentRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(d));

        parseTx.finalizeFailure(1L, "some error");

        assertEquals(DocStatus.FAILED.value(), d.getParseStatus());
        assertEquals("some error", d.getErrorMsg());
        verify(documentRepository).save(d);
    }

    @Test
    void finalizeFailure_noOp_whenDocNotFound() {
        when(documentRepository.findByIdForUpdate(1L)).thenReturn(Optional.empty());
        parseTx.finalizeFailure(1L, "some error");
        verify(documentRepository, never()).save(any());
    }
}