package com.ai.konwledgerepo.service.document;

import com.ai.konwledgerepo.common.AfterCommitExecutor;
import com.ai.konwledgerepo.common.BizException;
import com.ai.konwledgerepo.config.props.SeuFileProperties;
import com.ai.konwledgerepo.dto.ChunkResponse;
import com.ai.konwledgerepo.dto.DocumentResponse;
import com.ai.konwledgerepo.dto.VectorStats;
import com.ai.konwledgerepo.entity.ChunkStatus;
import com.ai.konwledgerepo.entity.DocStatus;
import com.ai.konwledgerepo.entity.Document;
import com.ai.konwledgerepo.entity.DocumentCurateLog;
import com.ai.konwledgerepo.entity.KnowledgeBase;
import com.ai.konwledgerepo.repository.BusinessKnowledgeRepository;
import com.ai.konwledgerepo.repository.ChunkRepository;
import com.ai.konwledgerepo.repository.ChunkStatusCount;
import com.ai.konwledgerepo.repository.DocumentCurateLogRepository;
import com.ai.konwledgerepo.repository.DocumentCurateRepository;
import com.ai.konwledgerepo.repository.DocumentRepository;
import com.ai.konwledgerepo.repository.QaPairRepository;
import com.ai.konwledgerepo.service.knowledgebase.KnowledgeBaseService;
import com.ai.konwledgerepo.service.vector.VectorIngestionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 文档业务：上传落盘、异步解析分块、列表/删除/重试/重命名/重建向量。
 *
 * 解析链路：上传 → 落盘 → 异步解析分块 → chunk 写入 MySQL（status=EMBEDDING）→ 向量化入 ES（→ INDEXED）。
 * 两条线各自独立：<b>parseStatus=SUCCESS 只代表分块已落库</b>，向量是否真进 ES 由 chunk 状态决定
 * （列表的 vector 计数与 errorMsg 说明这条线，向量化执行见 VectorIngestionService）。
 */
@Service
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

    private static final Set<String> ALLOWED_TYPES = Set.of("txt", "md", "html", "pdf", "docx", "pptx", "xlsx", "xls");

    /** 文件名长度上限：与 {@code kb_document.file_name varchar(255)} 对齐，在业务层先拦，避免 DB 完整性异常冒成"数据操作冲突" */
    static final int MAX_FILE_NAME_CHARS = 255;

    private final DocumentRepository documentRepository;
    private final ChunkRepository chunkRepository;
    private final DocumentCurateRepository curateRepository;
    private final DocumentCurateLogRepository curateLogRepository;
    private final BusinessKnowledgeRepository businessKnowledgeRepository;
    private final QaPairRepository qaPairRepository;
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
                           BusinessKnowledgeRepository businessKnowledgeRepository,
                           QaPairRepository qaPairRepository,
                           KnowledgeBaseService kbService,
                           VectorIngestionService vectorIngestionService,
                           DocumentParseExecutor parseExecutor,
                           AfterCommitExecutor afterCommitExecutor,
                           SeuFileProperties fileProps) {
        this.documentRepository = documentRepository;
        this.chunkRepository = chunkRepository;
        this.curateRepository = curateRepository;
        this.curateLogRepository = curateLogRepository;
        this.businessKnowledgeRepository = businessKnowledgeRepository;
        this.qaPairRepository = qaPairRepository;
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
     * 命中则事务内删除旧文档（MySQL chunk + ES 向量 + 初洗 md + 磁盘文件 + 记录）再建新。
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
     * 上传文档（含初洗门开关）：curateGate=true 时解析分块完成后停在初洗
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
        // 一次 group-by 取回全库 chunk 向量状态分布，避免逐文档 count（文档数 × 状态数）放大查询
        Map<Long, VectorStats> counts = vectorCounts(kbId);
        return documentRepository.findByKbIdOrderByIdDesc(kbId).stream()
                .map(d -> DocumentResponse.withVectorStats(d,
                        chunkRepository.countByDocIdAndCleanStatus(d.getId(), "SUSPECT"),
                        counts.getOrDefault(d.getId(), VectorStats.EMPTY)))
                .toList();
    }

    /**
     * 按知识库聚合每个文档的 chunk 向量状态计数（剔除口径见 {@link VectorStats} 文档）。
     */
    private Map<Long, VectorStats> vectorCounts(Long kbId) {
        Map<Long, int[]> raw = new HashMap<>();
        for (ChunkStatusCount row : chunkRepository.statusCountsByKb(kbId)) {
            String status = row.getStatus();
            String cleanStatus = row.getCleanStatus();
            if (status == null || ChunkStatus.FILTERED.is(status) || ChunkReviewService.CLEAN_SUSPECT.equals(cleanStatus)) {
                continue;
            }
            int[] acc = raw.computeIfAbsent(row.getDocId(), k -> new int[3]);
            long n = row.getCnt() == null ? 0L : row.getCnt();
            if (ChunkStatus.INDEXED.is(status)) {
                acc[0] += (int) n;
            } else if (ChunkStatus.EMBEDDING.is(status)) {
                acc[1] += (int) n;
            } else if (ChunkStatus.FAILED.is(status)) {
                acc[2] += (int) n;
            }
        }
        Map<Long, VectorStats> result = new HashMap<>(raw.size());
        raw.forEach((docId, acc) -> result.put(docId, new VectorStats(acc[0], acc[1], acc[2])));
        return result;
    }

    @Transactional
    public void delete(Long docId) {
        Document doc = getEntity(docId);
        deleteDoc(doc);
        evictKbCaches(doc.getKbId());
    }

    /** 清理文档全链路数据：MySQL chunk + ES 向量 + 初洗 md/审计 + 磁盘文件 + 记录（供删除与同名覆盖共用） */
    private void deleteDoc(Document doc) {
        // 删 MySQL chunk
        chunkRepository.deleteByDocId(doc.getId());
        // 删 ES chunk
        vectorIngestionService.deleteByDocId(doc.getId());
        // 删初洗 md（全部版本）与初洗/精修审计
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

    /**
     * 文档重命名（只改元数据与检索引用名，不重解析、不动磁盘文件）。
     * <p>
     * 已知语义取舍：{@code file_name} 同时是上传同名判重的键（{@link #upload} 的 replace 按 kbId +
     * 忽略大小写比对内存列表），改名后按<b>原始文件名</b>再次上传不再判为同名冲突（会新建一份）。
     * {@code kb_document} 上没有 file_name 唯一索引，DB 层零风险；此处仍做应用层查重，
     * 守住"同名即覆盖"这一用户可感知假设不被改名操作静默破坏。
     * <p>
     * 联动：ES 侧 {@code docName}（参与 BM25 打分、rerank 输入与问答引用展示）按 docId 批量更新；
     * 派生知识（业务知识/问答对）的 source_doc_name 同步；动作写 document_curate_log 审计（复用既有表）。
     * 历史会话的引用快照（kb_chat_message.refs）不回改——那是当时答案的取证记录。
     */
    @Transactional
    public Document rename(Long docId, String rawName, Long userId) {
        Document doc = getEntity(docId);
        String name = rawName == null ? "" : rawName.trim();
        if (name.isEmpty()) {
            throw new BizException("文件名不能为空");
        }
        if (name.length() > MAX_FILE_NAME_CHARS) {
            throw new BizException("文件名过长（最多 " + MAX_FILE_NAME_CHARS + " 字符）: " + brief(name));
        }
        if (name.contains("/") || name.contains("\\") || name.chars().anyMatch(Character::isISOControl)) {
            throw new BizException("文件名含非法字符（路径分隔符或控制字符）");
        }
        if (!extension(name).equals(doc.getFileType())) {
            throw new BizException("不允许修改扩展名（当前 ." + doc.getFileType() + "）：解析与分块按扩展名选择处理方式");
        }
        String old = doc.getFileName();
        if (name.equals(old)) {
            throw new BizException("新文件名与当前相同");
        }
        boolean duplicated = documentRepository.findByKbIdOrderByIdDesc(doc.getKbId()).stream()
                .anyMatch(d -> !d.getId().equals(docId) && name.equalsIgnoreCase(d.getFileName()));
        if (duplicated) {
            throw new BizException("同知识库已存在同名文档: " + name);
        }
        doc.setFileName(name);
        Document saved = documentRepository.saveAndFlush(doc);
        vectorIngestionService.renameInIndex(docId, name);
        businessKnowledgeRepository.updateSourceDocNameByDocId(docId, name);
        qaPairRepository.updateSourceDocNameByDocId(docId, name);
        recordLog(docId, "rename", old, name, userId);
        log.info("文档 {} 重命名：{} → {}（操作人 userId={}）", docId, old, name, userId);
        return saved;
    }

    /**
     * 只重建向量、不重新解析：向量化失败（模型未配置 / embedding 异常 / ES bulk 部分失败）后的救济通道。
     * <p>
     * 为什么需要它：此前唯一的救济是 {@link #retry}（全量重解析），对初洗门文档还会连带洗掉人工编辑的
     * md 历史版本；且 retry 会重新调用云端解析，成本与不确定性都高。本方法只把 FAILED 块退回
     * EMBEDDING 后重跑 ingest（幂等，已 INDEXED 的块天然被跳过）。
     *
     * @return 本次从 FAILED 退回待向量化的块数（0 表示无失败块，仅重跑遗漏的 EMBEDDING 块）
     */
    @Transactional
    public int reindex(Long docId) {
        Document doc = getEntity(docId);
        if (doc.isCurating()) {
            throw new BizException("文档处于初洗/精修流程，请先在精修页「确认向量化」");
        }
        String status = doc.getParseStatus();
        if (!DocStatus.SUCCESS.is(status) && !DocStatus.ERROR.is(status)) {
            throw new BizException("解析未成功（" + status + "），请用「重试」重新解析");
        }
        int reset = chunkRepository.resetFailedToEmbedding(docId);
        // 事务提交后再异步向量化：独立线程能读到已提交的 FAILED→EMBEDDING 变更（与 retry 同一套时序约束）
        afterCommitExecutor.runAfterCommit(() -> vectorIngestionService.ingestAsync(docId));
        log.info("文档 {} 触发重建向量（FAILED→EMBEDDING {} 块）", docId, reset);
        return reset;
    }

    /**
     * 文档级动作审计（复用 document_curate_log 既有表，无 DDL）。写失败不影响主操作，与初洗侧口径一致。
     */
    private void recordLog(Long docId, String action, String before, String after, Long userId) {
        try {
            DocumentCurateLog logRow = new DocumentCurateLog();
            logRow.setDocId(docId);
            logRow.setAction(action);
            logRow.setBeforeSummary(before);
            logRow.setAfterSummary(after);
            logRow.setUserId(userId);
            curateLogRepository.save(logRow);
        } catch (Exception e) {
            log.warn("文档审计写入失败（不影响主操作）docId={} action={}: {}", docId, action, e.getMessage());
        }
    }

    private void validate(MultipartFile file) {
        String original = file.getOriginalFilename();
        if (original == null || original.isBlank()) {
            throw new BizException("文件名为空，无法上传");
        }
        // 与 kb_document.file_name varchar(255) 同口径。Java 按 UTF-16 计数、MySQL 按字符计数，
        // 代理对（emoji）下本校验更严格——只会提前拒绝，不会放过一条让 DB 报错的名字。
        if (original.length() > MAX_FILE_NAME_CHARS) {
            throw new BizException("文件名过长（最多 " + MAX_FILE_NAME_CHARS + " 字符）: " + brief(original));
        }
        String ext = extension(original);
        if (!ALLOWED_TYPES.contains(ext)) {
            throw new BizException("仅支持 .txt / .md / .html / .pdf / .docx / .pptx / .xlsx / .xls 文件，当前: " + original);
        }
        if (file.isEmpty()) {
            throw new BizException("文件内容为空: " + original);
        }
        if (file.getSize() > maxFileSize) {
            throw new BizException("文件超过大小上限 " + (maxFileSize / 1024 / 1024) + "MB: " + original);
        }
    }

    /** 超长文件名回显用截断（保留头尾，避免整串刷屏） */
    private static String brief(String name) {
        return name.length() <= 60 ? name : name.substring(0, 40) + "…" + name.substring(name.length() - 10);
    }

    private Document persistFile(Long kbId, MultipartFile file, Long userId) {
        String ext = extension(file.getOriginalFilename());
        String storedName = UUID.randomUUID().toString().replace("-", "") + "." + ext;
        Path dir = Path.of(storagePath, String.valueOf(kbId));
        Path target = null;
        try {
            Files.createDirectories(dir);
            target = dir.resolve(storedName);
            file.transferTo(target);

            Document doc = new Document();
            doc.setKbId(kbId);
            doc.setFileName(file.getOriginalFilename());
            doc.setFileType(ext);
            doc.setFilePath(target.toString());
            doc.setFileSize(file.getSize());
            doc.setParseStatus(DocStatus.PENDING.value());
            doc.setCreatedBy(userId);
            // saveAndFlush：让字段超长等完整性异常在本方法内抛出（否则延迟到 commit，已逃出手工清理），
            // 从而删掉刚写入的磁盘文件——否则文件会永久孤儿留在 data/ 下（不在 git 里，删了找不回来）。
            return documentRepository.saveAndFlush(doc);
        } catch (IOException e) {
            deleteQuietly(target);
            throw new BizException("文件保存失败: " + e.getMessage());
        } catch (DataAccessException e) {
            deleteQuietly(target);
            log.warn("文档记录落库失败，已清理落盘文件 kbId={} file={}", kbId, file.getOriginalFilename(), e);
            throw e;
        }
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ex) {
            log.warn("清理孤儿上传文件失败 {}: {}", path, ex.getMessage());
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
