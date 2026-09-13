package com.ai.konwledgerepo.service.document;

import com.ai.konwledgerepo.common.AfterCommitExecutor;
import com.ai.konwledgerepo.common.BizException;
import com.ai.konwledgerepo.config.props.SeuFileProperties;
import com.ai.konwledgerepo.dto.ChunkPageResponse;
import com.ai.konwledgerepo.dto.ChunkResponse;
import com.ai.konwledgerepo.dto.DocumentResponse;
import com.ai.konwledgerepo.dto.ReindexResponse;
import com.ai.konwledgerepo.entity.Chunk;
import com.ai.konwledgerepo.entity.ChunkStatus;
import com.ai.konwledgerepo.entity.DocStatus;
import com.ai.konwledgerepo.entity.Document;
import com.ai.konwledgerepo.entity.KnowledgeBase;
import com.ai.konwledgerepo.repository.BusinessKnowledgeRepository;
import com.ai.konwledgerepo.repository.ChunkRepository;
import com.ai.konwledgerepo.repository.ChunkReviewLogRepository;
import com.ai.konwledgerepo.repository.ChunkStatusCount;
import com.ai.konwledgerepo.repository.DocumentCurateLogRepository;
import com.ai.konwledgerepo.repository.DocumentCurateRepository;
import com.ai.konwledgerepo.repository.DocumentRepository;
import com.ai.konwledgerepo.repository.QaPairRepository;
import com.ai.konwledgerepo.service.knowledgebase.KnowledgeBaseService;
import com.ai.konwledgerepo.service.vector.VectorIngestionService;
import com.ai.konwledgerepo.support.StorageTestSupport;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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
    private ChunkReviewLogRepository chunkReviewLogRepository;
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
        chunkReviewLogRepository = mock(ChunkReviewLogRepository.class);
        businessKnowledgeRepository = mock(BusinessKnowledgeRepository.class);
        qaPairRepository = mock(QaPairRepository.class);
        kbService = mock(KnowledgeBaseService.class);
        vectorIngestionService = mock(VectorIngestionService.class);
        parseExecutor = mock(DocumentParseExecutor.class);
        SeuFileProperties fileProps = new SeuFileProperties(tempDir.toString(), 20 * 1024 * 1024L);
        service = new DocumentService(documentRepository, chunkRepository, curateRepository, curateLogRepository,
                chunkReviewLogRepository,
                businessKnowledgeRepository, qaPairRepository,
                kbService, vectorIngestionService, parseExecutor, new AfterCommitExecutor(),
                StorageTestSupport.localBlobService(tempDir), fileProps);

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
        verify(businessKnowledgeRepository, never()).detachSourceDocumentByDocId(anyLong());
        verify(qaPairRepository, never()).detachSourceDocumentByDocId(anyLong());
        verify(vectorIngestionService, never()).detachSourceDocumentInIndex(anyLong());
        // AfterCommitExecutor 在无事务时内联执行 → parseAsync 被调用（retry 强制重新解析，不复用缓存）
        verify(parseExecutor).parseAsync(DOC_ID, false);
    }

    // ===== delete：清孤儿且保留结构化知识 =====

    @Test
    void delete_detachesKnowledgeAndCleansDocumentOwnedData() throws Exception {
        Path sourceFile = Files.createFile(tempDir.resolve("手册.pdf"));
        Document existing = doc("手册.pdf", "pdf", null);
        existing.setFilePath(sourceFile.toString());
        when(documentRepository.findById(DOC_ID)).thenReturn(Optional.of(existing));
        when(businessKnowledgeRepository.detachSourceDocumentByDocId(DOC_ID)).thenReturn(3);
        when(qaPairRepository.detachSourceDocumentByDocId(DOC_ID)).thenReturn(2);

        service.delete(DOC_ID);

        verify(businessKnowledgeRepository).detachSourceDocumentByDocId(DOC_ID);
        verify(qaPairRepository).detachSourceDocumentByDocId(DOC_ID);
        verify(vectorIngestionService).detachSourceDocumentInIndex(DOC_ID);
        verify(chunkReviewLogRepository).deleteByDocId(DOC_ID);
        verify(chunkRepository).deleteByDocId(DOC_ID);
        verify(vectorIngestionService).deleteByDocId(DOC_ID);
        verify(curateRepository).deleteByDocId(DOC_ID);
        verify(curateLogRepository).deleteByDocId(DOC_ID);
        verify(documentRepository).delete(existing);
        assertTrue(Files.notExists(sourceFile));
    }

    @Test
    void delete_legacyDocumentWithoutFilePath_stillCompletes() {
        Document existing = doc("旧记录.pdf", "pdf", null);
        existing.setFilePath(null);
        when(documentRepository.findById(DOC_ID)).thenReturn(Optional.of(existing));

        service.delete(DOC_ID);

        verify(documentRepository).delete(existing);
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

        ReindexResponse result = service.reindex(DOC_ID);

        assertEquals(3, result.reset());
        assertEquals(0, result.awaitingReview());
        // 无活动事务 → AfterCommitExecutor 内联执行；这里只验证向量化被触发（实跑在独立线程池）
        verify(vectorIngestionService).ingestAsync(DOC_ID);
    }

    /**
     * 全部块待人工审核（SUSPECT 走 DEFER，不进 ES）时，重建向量实为空操作：
     * 必须把 reset=0 与待审块数回传，前端才有依据提示"去精修页处置"，
     * 而不是像以前那样固定报"已触发"、把用户推向 retry（全量重解析：删块删向量 + 再花一次云端解析）。
     */
    @Test
    void reindex_allChunksAwaitingReview_reportsNoOpWithReason() {
        when(chunkRepository.resetFailedToEmbedding(DOC_ID)).thenReturn(0);
        when(chunkRepository.countByDocIdAndCleanStatus(DOC_ID, "SUSPECT")).thenReturn(10L);

        ReindexResponse result = service.reindex(DOC_ID);

        assertEquals(0, result.reset(), "无 FAILED 块可重建");
        assertEquals(10, result.awaitingReview(), "待人工审核块数要一起回传");
    }

    // ===== list：向量计数与待审核计数同源（单条 group-by，不再逐文档 count） =====

    @Test
    void list_countsBothVectorAndSuspectFromSingleGroupBy() {
        when(documentRepository.findByKbIdOrderByIdDesc(KB_ID))
                .thenReturn(List.of(doc("手册.pdf", "pdf", null)));
        when(chunkRepository.statusCountsByKb(KB_ID)).thenReturn(List.of(
                row(DOC_ID, "INDEXED", null, 6L),
                row(DOC_ID, "EMBEDDING", null, 1L),
                row(DOC_ID, "FAILED", null, 1L),
                row(DOC_ID, "EMBEDDING", "SUSPECT", 3L),
                row(DOC_ID, "FILTERED", "FILTERED", 2L)));

        List<DocumentResponse> list = service.list(KB_ID);

        assertEquals(1, list.size());
        DocumentResponse d = list.get(0);
        // SUSPECT 与 FILTERED 都不进向量分母（DEFER / 清洗丢弃都不算"应索引而未索引"）
        assertEquals(6, d.vector().indexed());
        assertEquals(1, d.vector().pending());
        assertEquals(1, d.vector().failed());
        assertEquals(8, d.vector().total());
        // 待审核块数来自同一条 group-by
        assertEquals(3L, d.suspectCount().longValue());
        // 回归锁：不得再按文档逐个 count（N 个文档 = N 次额外查询，与本聚合查询的初衷矛盾）
        verify(chunkRepository, never()).countByDocIdAndCleanStatus(anyLong(), anyString());
    }

    /**
     * 全部块待人工审核：向量三项全 0（{@code total()=0}），但 suspectCount 必须非 0 ——
     * 前端据此把向量列从中性 '-' 改成"待审核 N"，否则"零向量、零召回"在列表上完全看不出来。
     */
    @Test
    void list_allChunksAwaitingReview_keepsVectorZeroButReportsSuspect() {
        when(documentRepository.findByKbIdOrderByIdDesc(KB_ID))
                .thenReturn(List.of(doc("链接清单.md", "md", null)));
        when(chunkRepository.statusCountsByKb(KB_ID))
                .thenReturn(List.of(row(DOC_ID, "EMBEDDING", "SUSPECT", 10L)));

        DocumentResponse d = service.list(KB_ID).get(0);

        assertEquals(0, d.vector().total(), "DEFER：待审块不计入向量分母");
        assertEquals(10L, d.suspectCount().longValue(), "零召回的信号只能从这里出，不能一起被吞掉");
        verify(chunkRepository, never()).countByDocIdAndCleanStatus(anyLong(), anyString());
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

    /**
     * 回归锁（多文件上传失败隔离）：第 2 个文件落库失败回滚整个事务时，第 1 个文件已写入存储的
     * 对象必须被一并清理——行回滚了对象不会自动消失，此前会留成永久孤儿（原始件不可再生）。
     */
    @Test
    void upload_multiFileSecondFails_cleansFirstFileObject() throws Exception {
        MultipartFile first = new MockMultipartFile("files", "第一份.md", "text/markdown", "第一份内容".getBytes());
        MultipartFile second = new MockMultipartFile("files", "第二份.md", "text/markdown", "第二份内容".getBytes());
        when(documentRepository.saveAndFlush(any()))
                .thenAnswer(i -> {
                    Document d = i.getArgument(0);
                    d.setId(99L);
                    return d;
                })
                .thenThrow(new DataIntegrityViolationException("第二份文件落库失败"));

        assertThrows(DataIntegrityViolationException.class,
                () -> service.upload(KB_ID, List.of(first, second), 7L, false, false, false));

        try (var paths = Files.walk(tempDir)) {
            List<Path> leftovers = paths.filter(Files::isRegularFile).toList();
            assertTrue(leftovers.isEmpty(), "第二份文件失败后，第一份已传对象未清理: " + leftovers);
        }
        verify(parseExecutor, never()).parseAsync(anyLong(), anyBoolean());
    }

    // ===== 构造辅助 =====

    @Test
    void chunkPage_returnsPageItemsWithSuspectCount() {
        Chunk keep = chunk(11L, "KEEP");
        when(chunkRepository.pageByDocSeq(eq(DOC_ID), isNull(), any()))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(
                        List.of(keep), org.springframework.data.domain.PageRequest.of(0, 20), 42L));
        when(chunkRepository.countByDocIdAndCleanStatus(DOC_ID, "SUSPECT")).thenReturn(3L);

        ChunkPageResponse<ChunkResponse> resp = service.chunkPage(DOC_ID, 0, 20, null);

        assertEquals(1, resp.items().size());
        assertEquals(42L, resp.total());
        assertEquals(3L, resp.suspectCount());
        assertEquals(11L, resp.items().get(0).id());
    }

    @Test
    void chunkPage_passesCleanStatusFilterThrough() {
        when(chunkRepository.pageByDocSeq(eq(DOC_ID), eq("SUSPECT"), any()))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of()));

        service.chunkPage(DOC_ID, 1, 50, " SUSPECT ");

        verify(chunkRepository).pageByDocSeq(eq(DOC_ID), eq("SUSPECT"),
                eq(org.springframework.data.domain.PageRequest.of(1, 50)));
    }

    @Test
    void suspectChunkIds_returnsOnlySuspectChunkIds() {
        Chunk s1 = chunk(11L, "SUSPECT");
        Chunk s2 = chunk(12L, "SUSPECT");
        when(chunkRepository.findByDocIdAndCleanStatusOrderBySeqAsc(DOC_ID, "SUSPECT"))
                .thenReturn(List.of(s1, s2));

        List<Long> ids = service.suspectChunkIds(DOC_ID);

        assertEquals(List.of(11L, 12L), ids);
    }

    private static Chunk chunk(long id, String cleanStatus) {
        Chunk c = new Chunk();
        c.setId(id);
        c.setDocId(DOC_ID);
        c.setKbId(KB_ID);
        c.setSeq(1);
        c.setContent("内容 " + id);
        c.setStatus(ChunkStatus.EMBEDDING.value());
        c.setCleanStatus(cleanStatus);
        return c;
    }

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

    /** statusCountsByKb 的 group-by 投影行（(docId, status, cleanStatus) 粒度） */
    private static ChunkStatusCount row(Long docId, String status, String cleanStatus, Long cnt) {
        return new ChunkStatusCount() {
            @Override
            public Long getDocId() {
                return docId;
            }

            @Override
            public String getStatus() {
                return status;
            }

            @Override
            public String getCleanStatus() {
                return cleanStatus;
            }

            @Override
            public Long getCnt() {
                return cnt;
            }
        };
    }
}
