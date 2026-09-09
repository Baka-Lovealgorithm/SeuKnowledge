package com.ai.konwledgerepo.service.document;

import com.ai.konwledgerepo.common.AfterCommitExecutor;
import com.ai.konwledgerepo.common.BizException;
import com.ai.konwledgerepo.config.props.SeuFileProperties;
import com.ai.konwledgerepo.entity.DocStatus;
import com.ai.konwledgerepo.entity.Document;
import com.ai.konwledgerepo.entity.KnowledgeBase;
import com.ai.konwledgerepo.repository.BusinessKnowledgeRepository;
import com.ai.konwledgerepo.repository.ChunkRepository;
import com.ai.konwledgerepo.repository.DocumentCurateLogRepository;
import com.ai.konwledgerepo.repository.DocumentCurateRepository;
import com.ai.konwledgerepo.repository.DocumentRepository;
import com.ai.konwledgerepo.repository.QaPairRepository;
import com.ai.konwledgerepo.service.knowledgebase.KnowledgeBaseService;
import com.ai.konwledgerepo.service.vector.VectorIngestionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DocumentService 单元测试：retry 的 CAS 路径（F-7）、rename 校验与联动、reindex 守卫、
 * 上传文件名长度校验、以及落库失败时的孤儿文件清理。
 */
class DocumentServiceTest {

    private static final long DOC_ID = 1L;
    private static final long KB_ID = 10L;
    private static final long WS_ID = 1L;

    private DocumentRepository documentRepository;
    private ChunkRepository chunkRepository;
    private DocumentCurateRepository curateRepository;
    private DocumentCurateLogRepository curateLogRepository;
    private BusinessKnowledgeRepository businessKnowledgeRepository;
    private QaPairRepository qaPairRepository;
    private KnowledgeBaseService kbService;
    private VectorIngestionService vectorIngestionService;
    private DocumentParseExecutor parseExecutor;
    private DocumentService service;
    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        documentRepository = mock(DocumentRepository.class);
        chunkRepository = mock(ChunkRepository.class);
        curateRepository = mock(DocumentCurateRepository.class);
        curateLogRepository = mock(DocumentCurateLogRepository.class);
        businessKnowledgeRepository = mock(BusinessKnowledgeRepository.class);
        qaPairRepository = mock(QaPairRepository.class);
        kbService = mock(KnowledgeBaseService.class);
        vectorIngestionService = mock(VectorIngestionService.class);
        parseExecutor = mock(DocumentParseExecutor.class);
        SeuFileProperties fileProps = new SeuFileProperties(tempDir.toString(), 20 * 1024 * 1024L);
        service = new DocumentService(documentRepository, chunkRepository, curateRepository, curateLogRepository,
                businessKnowledgeRepository, qaPairRepository,
                kbService, vectorIngestionService, parseExecutor, new AfterCommitExecutor(), fileProps);

        when(documentRepository.findById(DOC_ID)).thenReturn(Optional.of(doc("手册.pdf", "pdf", null)));
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(KB_ID);
        kb.setWorkspaceId(WS_ID);
        when(kbService.getEntity(KB_ID)).thenReturn(kb);
    }

    // ===== retry（F-7 CAS） =====

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

    // ===== rename：校验分支 =====

    @Test
    void rename_blank_throws() {
        BizException ex = assertThrows(BizException.class, () -> service.rename(DOC_ID, "   ", 7L));
        assertEquals("文件名不能为空", ex.getMessage());
    }

    @Test
    void rename_tooLong_throwsWithLimit() {
        String name = "a".repeat(252) + ".pdf";

        BizException ex = assertThrows(BizException.class, () -> service.rename(DOC_ID, name, 7L));
        assertTrue(ex.getMessage().startsWith("文件名过长（最多 255 字符）"), ex.getMessage());
    }

    @Test
    void rename_pathSeparator_throws() {
        BizException ex = assertThrows(BizException.class, () -> service.rename(DOC_ID, "a/b.pdf", 7L));
        assertEquals("文件名含非法字符（路径分隔符或控制字符）", ex.getMessage());
    }

    @Test
    void rename_extensionChanged_throws() {
        BizException ex = assertThrows(BizException.class, () -> service.rename(DOC_ID, "手册.docx", 7L));
        assertTrue(ex.getMessage().startsWith("不允许修改扩展名"), ex.getMessage());
    }

    @Test
    void rename_sameNameAfterTrim_throws() {
        BizException ex = assertThrows(BizException.class, () -> service.rename(DOC_ID, " 手册.pdf ", 7L));
        assertEquals("新文件名与当前相同", ex.getMessage());
    }

    @Test
    void rename_duplicatedInKb_throws() {
        when(documentRepository.findByKbIdOrderByIdDesc(KB_ID))
                .thenReturn(List.of(doc("手册.pdf", "pdf", null), otherDoc(2L, "新版手册.pdf")));

        BizException ex = assertThrows(BizException.class, () -> service.rename(DOC_ID, "新版手册.pdf", 7L));
        assertEquals("同知识库已存在同名文档: 新版手册.pdf", ex.getMessage());
        verify(documentRepository, never()).saveAndFlush(any());
    }

    // ===== rename：成功路径与联动 =====

    @Test
    void rename_success_syncsEsAndDerivedNamesAndLogs() {
        when(documentRepository.findByKbIdOrderByIdDesc(KB_ID))
                .thenReturn(List.of(doc("手册.pdf", "pdf", null)));
        when(documentRepository.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));

        Document saved = service.rename(DOC_ID, "产品手册.pdf", 7L);

        assertEquals("产品手册.pdf", saved.getFileName());
        // ES 引用名（参与 BM25 与 rerank 输入、问答引用展示）按 docId 一并改
        verify(vectorIngestionService).renameInIndex(DOC_ID, "产品手册.pdf");
        // 派生知识冗余展示名同步（仅 UPDATE，不动表结构）
        verify(businessKnowledgeRepository).updateSourceDocNameByDocId(DOC_ID, "产品手册.pdf");
        verify(qaPairRepository).updateSourceDocNameByDocId(DOC_ID, "产品手册.pdf");
        // 复用 document_curate_log 留痕
        verify(curateLogRepository).save(any());
    }

    // ===== reindex =====

    @Test
    void reindex_whileCurating_rejected() {
        when(documentRepository.findById(DOC_ID))
                .thenReturn(Optional.of(doc("手册.pdf", "pdf", Document.CURATE_PREVIEWING)));

        BizException ex = assertThrows(BizException.class, () -> service.reindex(DOC_ID));
        assertTrue(ex.getMessage().contains("初洗/精修"), ex.getMessage());
        verify(vectorIngestionService, never()).ingestAsync(anyLong());
    }

    @Test
    void reindex_parseNotDone_rejected() {
        Document parsing = doc("手册.pdf", "pdf", null);
        parsing.setParseStatus(DocStatus.PARSING.value());
        when(documentRepository.findById(DOC_ID)).thenReturn(Optional.of(parsing));

        BizException ex = assertThrows(BizException.class, () -> service.reindex(DOC_ID));
        assertTrue(ex.getMessage().contains("解析未成功"), ex.getMessage());
    }

    @Test
    void reindex_resetsFailedAndTriggersIngest() {
        Document errored = doc("手册.pdf", "pdf", null);
        errored.setParseStatus(DocStatus.ERROR.value());
        when(documentRepository.findById(DOC_ID)).thenReturn(Optional.of(errored));
        when(chunkRepository.resetFailedToEmbedding(DOC_ID)).thenReturn(3);

        int reset = service.reindex(DOC_ID);

        assertEquals(3, reset);
        // 无活动事务 → AfterCommitExecutor 内联执行；这里只验证向量化被触发（实跑在独立线程池）
        verify(vectorIngestionService).ingestAsync(DOC_ID);
    }

    // ===== 上传：文件名长度与孤儿文件 =====

    @Test
    void upload_fileNameTooLong_rejectedBeforeTouchingDisk() {
        String longName = "b".repeat(256) + ".md"; // 259 字符 > 255
        MultipartFile file = new MockMultipartFile("files", longName, "text/markdown", "内容".getBytes());

        BizException ex = assertThrows(BizException.class,
                () -> service.upload(KB_ID, List.of(file), 7L, false, false, false));
        assertTrue(ex.getMessage().startsWith("文件名过长（最多 255 字符）"), ex.getMessage());
        verify(documentRepository, never()).saveAndFlush(any());
    }

    @Test
    void upload_persistSucceeds_createsRecordAndTriggersParse() {
        MultipartFile file = new MockMultipartFile("files", "小文档.md", "text/markdown", "# 标题\n内容".getBytes());
        when(documentRepository.saveAndFlush(any())).thenAnswer(i -> {
            Document d = i.getArgument(0);
            d.setId(99L);
            return d;
        });

        List<Document> saved = service.upload(KB_ID, List.of(file), 7L, false, false, false);

        assertEquals(1, saved.size());
        assertEquals("小文档.md", saved.get(0).getFileName());
        assertEquals(DocStatus.PENDING.value(), saved.get(0).getParseStatus());
        verify(parseExecutor).parseAsync(99L, false);
    }

    @Test
    void upload_dbRejectsFileName_cleansOrphanFileOnDisk() throws Exception {
        MultipartFile file = new MockMultipartFile("files", "脏文件.md", "text/markdown", "内容".getBytes());
        when(documentRepository.saveAndFlush(any()))
                .thenThrow(new DataIntegrityViolationException("Data too long for column 'file_name' at row 1"));

        assertThrows(DataIntegrityViolationException.class,
                () -> service.upload(KB_ID, List.of(file), 7L, false, false, false));

        // 事务回滚不会删已落盘文件：孤儿必须由 persistFile 自己清（data/ 不在 git 里，留着就是永久垃圾）
        try (var paths = Files.walk(tempDir)) {
            List<Path> leftovers = paths.filter(Files::isRegularFile).toList();
            assertTrue(leftovers.isEmpty(), "落库失败后仍残留磁盘文件: " + leftovers);
        }
    }

    // ===== 构造辅助 =====

    private static Document doc(String fileName, String fileType, String curateStatus) {
        Document d = new Document();
        d.setId(DOC_ID);
        d.setKbId(KB_ID);
        d.setFileName(fileName);
        d.setFileType(fileType);
        d.setParseStatus(DocStatus.SUCCESS.value());
        d.setCurateStatus(curateStatus);
        return d;
    }

    private static Document otherDoc(Long id, String fileName) {
        Document d = new Document();
        d.setId(id);
        d.setKbId(KB_ID);
        d.setFileName(fileName);
        d.setFileType("pdf");
        d.setParseStatus(DocStatus.SUCCESS.value());
        return d;
    }
}
