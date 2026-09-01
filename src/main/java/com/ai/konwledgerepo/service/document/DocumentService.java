package com.ai.konwledgerepo.service.document;

import com.ai.konwledgerepo.common.AfterCommitExecutor;
import com.ai.konwledgerepo.common.BizException;
import com.ai.konwledgerepo.config.props.SeuFileProperties;
import com.ai.konwledgerepo.dto.ChunkResponse;
import com.ai.konwledgerepo.dto.DocumentResponse;
import com.ai.konwledgerepo.entity.Chunk;
import com.ai.konwledgerepo.entity.DocStatus;
import com.ai.konwledgerepo.entity.Document;
import com.ai.konwledgerepo.entity.KnowledgeBase;
import com.ai.konwledgerepo.repository.ChunkRepository;
import com.ai.konwledgerepo.repository.DocumentCurateLogRepository;
import com.ai.konwledgerepo.repository.DocumentCurateRepository;
import com.ai.konwledgerepo.repository.DocumentRepository;
import com.ai.konwledgerepo.service.knowledgebase.KnowledgeBaseService;
import com.ai.konwledgerepo.service.vector.VectorIngestionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 文档业务：上传落盘、异步解析分块、列表/删除/重试。
 *
 * 解析链路：上传 → 落盘 → 异步解析分块 → chunk 写入 MySQL（EMBEDDING，待向量化）。
 * 向量化与 ES 写入由 VectorIngestionService 在 M3 模型配置就绪后接入。
 */
@Service
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

    private static final Set<String> ALLOWED_TYPES = Set.of("txt", "md", "pdf", "docx", "pptx", "xlsx", "xls");

    private final DocumentRepository documentRepository;
    private final ChunkRepository chunkRepository;
    private final DocumentCurateRepository curateRepository;
    private final DocumentCurateLogRepository curateLogRepository;
    private final KnowledgeBaseService kbService;
    private final VectorIngestionService vectorIngestionService;
    private final DocumentParseExecutor parseExecutor;
    private final AfterCommitExecutor afterCommitExecutor;
    private final String storagePath;
    private final long maxFileSize;

    public DocumentService(DocumentRepository documentRepository,
                           ChunkRepository chunkRepository,
                           DocumentCurateRepository curateRepository,
                           DocumentCurateLogRepository curateLogRepository,
                           KnowledgeBaseService kbService,
                           VectorIngestionService vectorIngestionService,
                           DocumentParseExecutor parseExecutor,
                           AfterCommitExecutor afterCommitExecutor,
                           SeuFileProperties fileProps) {
        this.documentRepository = documentRepository;
        this.chunkRepository = chunkRepository;
        this.curateRepository = curateRepository;
        this.curateLogRepository = curateLogRepository;
        this.kbService = kbService;
        this.vectorIngestionService = vectorIngestionService;
        this.parseExecutor = parseExecutor;
        this.afterCommitExecutor = afterCommitExecutor;
        this.storagePath = fileProps.storagePath();
        this.maxFileSize = fileProps.maxSize();
    }

    @Transactional
    public List<Document> upload(Long kbId, List<MultipartFile> files, Long userId) {
        return upload(kbId, files, userId, false, false, false);
    }

    /**
     * 上传文档（可替换同名旧文档）：replace=true 时按 kbId + fileName（大小写不敏感）查同名，
     * 命中则事务内删除旧文档（MySQL chunk + ES 向量 + 策展 md + 磁盘文件 + 记录）再建新。
     *
     * @param reuseCache 用户是否选择复用解析缓存：true 时解析阶段查文件哈希缓存，
     *                   同内容文件跳过 LlamaParse 复用逐页 markdown；false 全量解析
     *                   （缓存命中与否均不自动复用，行为由用户选择）。
     */
    @Transactional
    public List<Document> upload(Long kbId, List<MultipartFile> files, Long userId, boolean replace, boolean reuseCache) {
        return upload(kbId, files, userId, replace, reuseCache, false);
    }

    /**
     * 上传文档（含策展门开关）：curateGate=true 时解析分块完成后停在展示门
     * （PREVIEWING，不向量化），人工决断后才向量化；false 完全照旧自动链路。
     */
    @Transactional
    public List<Document> upload(Long kbId, List<MultipartFile> files, Long userId, boolean replace, boolean reuseCache,
                                 boolean curateGate) {
        KnowledgeBase kb = kbService.getEntity(kbId);
        List<Document> saved = new ArrayList<>();
        for (MultipartFile file : files) {
            validate(file);
            if (replace) {
                String name = file.getOriginalFilename();
                documentRepository.findByKbIdOrderByIdDesc(kbId).stream()
                        .filter(d -> name != null && name.equalsIgnoreCase(d.getFileName()))
                        .findFirst()
                        .ifPresent(this::deleteDoc);
            }
            Document doc = persistFile(kbId, file, userId);
            doc.setReuseCache(reuseCache);
            doc.setCurateRequired(curateGate);
            saved.add(doc);
        }
        // 异步解析分块：独立 bean 承载 @Async，挂到事务提交后触发（避免异步线程读不到未提交数据）
        for (Document doc : saved) {
            afterCommitExecutor.runAfterCommit(() -> parseExecutor.parseAsync(doc.getId(), doc.isReuseCache()));
        }
        // 文档计数与知识库列表缓存失效
        kbService.evictDocCount(kbId);
        kbService.evictKbList(kb.getWorkspaceId());
        return saved;
    }

    public List<DocumentResponse> list(Long kbId) {
        return documentRepository.findByKbIdOrderByIdDesc(kbId).stream()
                .map(d -> DocumentResponse.withSuspectCount(d, chunkRepository.countByDocIdAndCleanStatus(d.getId(), "SUSPECT")))
                .toList();
    }

    @Transactional
    public void delete(Long docId) {
        Document doc = getEntity(docId);
        deleteDoc(doc);
        evictKbCaches(doc.getKbId());
    }

    /** 清理文档全链路数据：MySQL chunk + ES 向量 + 策展 md/审计 + 磁盘文件 + 记录（供删除与同名覆盖共用） */
    private void deleteDoc(Document doc) {
        // 删 MySQL chunk
        chunkRepository.deleteByDocId(doc.getId());
        // 删 ES chunk
        vectorIngestionService.deleteByDocId(doc.getId());
        // 删策展 md（全部版本）与策展审计
        curateRepository.deleteByDocId(doc.getId());
        curateLogRepository.deleteByDocId(doc.getId());
        // 删文件
        try {
            Files.deleteIfExists(Path.of(doc.getFilePath()));
        } catch (IOException e) {
            log.warn("删除文档文件失败: {}", doc.getFilePath(), e);
        }
        documentRepository.delete(doc);
    }

    /** 文档变更后失效知识库计数与列表缓存 */
    private void evictKbCaches(Long kbId) {
        kbService.evictDocCount(kbId);
        try {
            KnowledgeBase kb = kbService.getEntity(kbId);
            kbService.evictKbList(kb.getWorkspaceId());
        } catch (Exception e) {
            // 知识库已不存在时仅失效计数，列表由 TTL 兜底
            log.debug("失效知识库列表缓存跳过（知识库 {} 不存在）", kbId);
        }
    }

    public List<ChunkResponse> chunks(Long docId) {
        getEntity(docId);
        return chunkRepository.findByDocIdOrderBySeqAsc(docId).stream()
                .map(ChunkResponse::from)
                .toList();
    }

    /** 解析失败重试：CAS 仅允许从 SUCCESS/FAILED/ERROR 进入 PENDING，0 行 → 解析中或已删除 */
    @Transactional
    public void retry(Long docId) {
        int updated = documentRepository.casPendingForRetry(docId);
        if (updated == 0) {
            if (documentRepository.existsById(docId)) {
                throw new BizException("文档解析进行中，无法重试");
            }
            throw new BizException("文档不存在或已被删除");
        }
        // 清空旧分块与向量（幂等），重新触发解析
        chunkRepository.deleteByDocId(docId);
        vectorIngestionService.deleteByDocId(docId);
        evictKbCaches(documentRepository.findById(docId).orElseThrow().getKbId());
        // 事务提交后异步重新解析（retry 为强制重新解析，不复用缓存）
        afterCommitExecutor.runAfterCommit(() -> parseExecutor.parseAsync(docId, false));
    }

    private void validate(MultipartFile file) {
        String original = file.getOriginalFilename();
        String ext = extension(original);
        if (!ALLOWED_TYPES.contains(ext)) {
            throw new BizException("仅支持 .txt / .md / .pdf / .docx / .pptx / .xlsx / .xls 文件，当前: " + (original == null ? "未知" : original));
        }
        if (file.isEmpty()) {
            throw new BizException("文件内容为空: " + original);
        }
        if (file.getSize() > maxFileSize) {
            throw new BizException("文件超过大小上限 " + (maxFileSize / 1024 / 1024) + "MB: " + original);
        }
    }

    private Document persistFile(Long kbId, MultipartFile file, Long userId) {
        try {
            String ext = extension(file.getOriginalFilename());
            String storedName = UUID.randomUUID().toString().replace("-", "") + "." + ext;
            Path dir = Path.of(storagePath, String.valueOf(kbId));
            Files.createDirectories(dir);
            Path target = dir.resolve(storedName);
            file.transferTo(target);

            Document doc = new Document();
            doc.setKbId(kbId);
            doc.setFileName(file.getOriginalFilename());
            doc.setFileType(ext);
            doc.setFilePath(target.toString());
            doc.setFileSize(file.getSize());
            doc.setParseStatus(DocStatus.PENDING.value());
            doc.setCreatedBy(userId);
            return documentRepository.save(doc);
        } catch (IOException e) {
            throw new BizException("文件保存失败: " + e.getMessage());
        }
    }

    private Document getEntity(Long docId) {
        return documentRepository.findById(docId)
                .orElseThrow(() -> new BizException("文档不存在"));
    }

    private static String extension(String filename) {
        if (filename == null || !filename.contains(".")) {
            return "";
        }
        return filename.substring(filename.lastIndexOf('.') + 1).toLowerCase();
    }
}
