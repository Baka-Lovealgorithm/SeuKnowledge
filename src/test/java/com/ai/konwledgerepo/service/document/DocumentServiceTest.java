package com.ai.konwledgerepo.service.document;

import com.ai.konwledgerepo.common.AfterCommitExecutor;
import com.ai.konwledgerepo.common.BizException;
import com.ai.konwledgerepo.config.props.SeuFileProperties;
import com.ai.konwledgerepo.entity.Document;
import com.ai.konwledgerepo.entity.KnowledgeBase;
import com.ai.konwledgerepo.repository.ChunkRepository;
import com.ai.konwledgerepo.repository.DocumentCurateLogRepository;
import com.ai.konwledgerepo.repository.DocumentCurateRepository;
import com.ai.konwledgerepo.repository.DocumentRepository;
import com.ai.konwledgerepo.service.knowledgebase.KnowledgeBaseService;
import com.ai.konwledgerepo.service.vector.VectorIngestionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DocumentService 单元测试：retry 的 CAS 路径（F-7）。
 * 覆盖：解析进行中拒绝、文档已删除拒绝、成功路径。
 */
class DocumentServiceTest {

    private static final long DOC_ID = 1L;
    private static final long KB_ID = 10L;
    private static final long WS_ID = 1L;

    private DocumentRepository documentRepository;
    private ChunkRepository chunkRepository;
    private DocumentCurateRepository curateRepository;
    private DocumentCurateLogRepository curateLogRepository;
    private KnowledgeBaseService kbService;
    private VectorIngestionService vectorIngestionService;
    private DocumentParseExecutor parseExecutor;
    private DocumentService service;

    @BeforeEach
    void setUp() {
        documentRepository = mock(DocumentRepository.class);
        chunkRepository = mock(ChunkRepository.class);
        curateRepository = mock(DocumentCurateRepository.class);
        curateLogRepository = mock(DocumentCurateLogRepository.class);
        kbService = mock(KnowledgeBaseService.class);
        vectorIngestionService = mock(VectorIngestionService.class);
        parseExecutor = mock(DocumentParseExecutor.class);
        SeuFileProperties fileProps = new SeuFileProperties("./data/files", 20 * 1024 * 1024L);
        service = new DocumentService(documentRepository, chunkRepository, curateRepository, curateLogRepository,
                kbService, vectorIngestionService, parseExecutor, new AfterCommitExecutor(), fileProps);

        Document doc = new Document();
        doc.setId(DOC_ID);
        doc.setKbId(KB_ID);
        when(documentRepository.findById(DOC_ID)).thenReturn(Optional.of(doc));
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(KB_ID);
        kb.setWorkspaceId(WS_ID);
        when(kbService.getEntity(KB_ID)).thenReturn(kb);
    }

    @Test
    void retry_casZeroRows_docExists_throwsParsing() {
        when(documentRepository.casPendingForRetry(DOC_ID)).thenReturn(0);
        when(documentRepository.existsById(DOC_ID)).thenReturn(true);

        BizException ex = assertThrows(BizException.class, () -> service.retry(DOC_ID));
        assertEquals("文档解析进行中，无法重试", ex.getMessage());
        verify(chunkRepository, never()).deleteByDocId(anyLong());
        verify(parseExecutor, never()).parseAsync(anyLong(), anyBoolean());
    }

    @Test
    void retry_casZeroRows_docNotExists_throwsNotFound() {
        when(documentRepository.casPendingForRetry(DOC_ID)).thenReturn(0);
        when(documentRepository.existsById(DOC_ID)).thenReturn(false);

        BizException ex = assertThrows(BizException.class, () -> service.retry(DOC_ID));
        assertEquals("文档不存在或已被删除", ex.getMessage());
        verify(chunkRepository, never()).deleteByDocId(anyLong());
    }

    @Test
    void retry_casSuccess_clearsAndTriggersParse() {
        when(documentRepository.casPendingForRetry(DOC_ID)).thenReturn(1);

        service.retry(DOC_ID);

        verify(chunkRepository).deleteByDocId(DOC_ID);
        verify(vectorIngestionService).deleteByDocId(DOC_ID);
        // AfterCommitExecutor 在无事务时内联执行 → parseAsync 被调用（retry 强制重新解析，不复用缓存）
        verify(parseExecutor).parseAsync(DOC_ID, false);
    }
}