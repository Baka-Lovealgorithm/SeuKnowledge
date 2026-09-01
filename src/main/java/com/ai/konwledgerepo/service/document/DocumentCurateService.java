package com.ai.konwledgerepo.service.document;

import com.ai.konwledgerepo.common.AfterCommitExecutor;
import com.ai.konwledgerepo.common.BizException;
import com.ai.konwledgerepo.dto.ChunkReviewResponse;
import com.ai.konwledgerepo.entity.Chunk;
import com.ai.konwledgerepo.entity.ChunkReviewLog;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 文档人工策展（md 清洗 + 展示门）：对解析后的逐页 markdown 提供在线编辑、重分块，
 * 以及"分块后、向量化前"的决断门（展示门 PREVIEWING → 已接受 ACCEPTED → 确认后统一向量化）。
 * <p>
 * 流程（仅 curateRequired=true 且 LlamaParse 产物 pdf/docx）：
 * <pre>
 * 解析完成 → saveInitialMd（落 md v1）→ finalizeSuccessGated（落 chunk + PREVIEWING）
 * PREVIEWING：展示门只读；saveMd（编辑 md → 重分块，可反复）或 accept
 * ACCEPTED：后悔通道关闭（禁编辑 md/重分块）；editChunk/dropChunk/keepChunk 逐块精修
 * confirm：统一向量化（ingest），curateStatus 清空，文档回到照旧
 * </pre>
 * 红线：PREVIEWING 下 chunk 只读；ACCEPTED 下 md 冻结；确认前不触发任何 ES 写入
 * （向量化统一在 confirm；未处置 SUSPECT 由 ingest 的 DEFER 过滤自然不进 ES）。
 */
@Service
public class DocumentCurateService {

    private static final Logger log = LoggerFactory.getLogger(DocumentCurateService.class);

    /** LlamaParse 导出页标记：<!-- PAGE N -->（导出与解析回读的规范格式） */
    private static final Pattern PAGE_MARK = Pattern.compile("<!--\\s*PAGE\\s*(\\d+)\\s*-->");

    private final DocumentRepository documentRepository;
    private final ChunkRepository chunkRepository;
    private final DocumentCurateRepository curateRepository;
    private final DocumentCurateLogRepository curateLogRepository;
    private final ChunkReviewLogRepository reviewLogRepository;
    private final DocumentParserService parserService;
    private final DocumentCleanService documentCleanService;
    private final DocumentParseTx parseTx;
    private final VectorIngestionService vectorIngestionService;
    private final AfterCommitExecutor afterCommitExecutor;

    public DocumentCurateService(DocumentRepository documentRepository,
                                 ChunkRepository chunkRepository,
                                 DocumentCurateRepository curateRepository,
                                 DocumentCurateLogRepository curateLogRepository,
                                 ChunkReviewLogRepository reviewLogRepository,
                                 DocumentParserService parserService,
                                 DocumentCleanService documentCleanService,
                                 DocumentParseTx parseTx,
                                 VectorIngestionService vectorIngestionService,
                                 AfterCommitExecutor afterCommitExecutor) {
        this.documentRepository = documentRepository;
        this.chunkRepository = chunkRepository;
        this.curateRepository = curateRepository;
        this.curateLogRepository = curateLogRepository;
        this.reviewLogRepository = reviewLogRepository;
        this.parserService = parserService;
        this.documentCleanService = documentCleanService;
        this.parseTx = parseTx;
        this.vectorIngestionService = vectorIngestionService;
        this.afterCommitExecutor = afterCommitExecutor;
    }

    // ==================== 查询 ====================

    /** 策展门队列项（文档 + 状态 + chunk/待处理 SUSPECT 计数） */
    public record CurateQueueItem(Long docId, Long kbId, String fileName, String curateStatus,
                                  Integer chunkCount, Long suspectCount) {
    }

    /** 单文档策展信息（前端处理页头部） */
    public record CurateDocInfo(Long docId, String fileName, String curateStatus, Integer chunkCount,
                                Long suspectCount) {
    }

    /** 单文档策展信息（含当前状态，供处理页展示与按钮分支） */
    public CurateDocInfo docInfo(Long docId) {
        Document doc = requireDoc(docId);
        return new CurateDocInfo(doc.getId(), doc.getFileName(), doc.getCurateStatus(), doc.getChunkCount(),
                chunkRepository.countByDocIdAndCleanStatus(docId, "SUSPECT"));
    }

    /** 策展门队列：知识库下所有处于展示门/已接受的文档 */
    public List<CurateQueueItem> queue(Long kbId) {
        List<Document> docs = documentRepository.findByKbIdAndCurateStatusInOrderByIdDesc(
                kbId, List.of(Document.CURATE_PREVIEWING, Document.CURATE_ACCEPTED));
        if (docs.isEmpty()) {
            return List.of();
        }
        return docs.stream()
                .map(d -> new CurateQueueItem(d.getId(), d.getKbId(), d.getFileName(), d.getCurateStatus(),
                        d.getChunkCount(),
                        chunkRepository.countByDocIdAndCleanStatus(d.getId(), "SUSPECT")))
                .toList();
    }

    /** 策展文档全部 chunk（SUSPECT 优先展示，供前端展示门/精修列表） */
    public List<ChunkReviewResponse> chunks(Long docId) {
        Document doc = requireDoc(docId);
        List<Chunk> chunks = chunkRepository.findByDocIdOrderBySeqAsc(docId);
        List<ChunkReviewResponse> resp = chunks.stream()
                .map(c -> ChunkReviewResponse.of(c, doc.getFileName()))
                .toList();
        // SUSPECT 优先（前端"优先展示 suspect"），其余按 seq
        return resp.stream()
                .sorted(java.util.Comparator
                        .comparing((ChunkReviewResponse r) -> !"SUSPECT".equals(r.cleanStatus()) ? 1 : 0)
                        .thenComparing(ChunkReviewResponse::seq))
                .toList();
    }

    /** 取最新版本整篇 md（页标记拼回，供在线编辑回显） */
    public String getMd(Long docId) {
        requireDoc(docId);
        List<DocumentCurate> pages = curateRepository.findByDocIdOrderByVersionDescPageNumAsc(docId);
        if (pages.isEmpty()) {
            throw new BizException("文档尚无策展 md（可能未启用策展门或解析未完成）");
        }
        int version = pages.get(0).getVersion();
        StringBuilder sb = new StringBuilder();
        for (DocumentCurate row : pages) {
            if (row.getVersion() != version) {
                break;
            }
            sb.append("\n<!-- PAGE ").append(row.getPageNum()).append(" -->\n").append(row.getContent());
        }
        return sb.toString();
    }

    // ==================== 初始落库（解析链路勾选路径调用） ====================

    /**
     * 初始解析落库：逐页 markdown → kb_document_curate version=1（幂等：已有则跳过）。
     * 由 {@link DocumentParseExecutor} 在 LlamaParse 解析完成后调用（事务内）。
     */
    @Transactional
    public void saveInitialMd(Long docId, List<LlamaParseService.PageMarkdown> pages, Long userId) {
        if (curateRepository.existsByDocId(docId)) {
            log.debug("saveInitialMd 跳过：文档 {} 已有策展 md", docId);
            return;
        }
        List<DocumentCurate> rows = new ArrayList<>();
        for (LlamaParseService.PageMarkdown p : pages) {
            if (p.markdown() == null || p.markdown().isBlank()) {
                continue;
            }
            DocumentCurate row = new DocumentCurate();
            row.setDocId(docId);
            row.setVersion(1);
            row.setPageNum(p.pageNumber());
            row.setContent(p.markdown());
            row.setUpdatedBy(userId == null ? 0L : userId);
            rows.add(row);
        }
        if (rows.isEmpty()) {
            log.warn("saveInitialMd：文档 {} 解析结果无可用页面，不落策展 md", docId);
            return;
        }
        curateRepository.saveAll(rows);
        recordLog(docId, "save_md", null, "v1 " + rows.size() + " 页", userId);
    }

    // ==================== 编辑 md 并重分块 ====================

    /** 保存 md 后的重分块结果摘要 */
    public record RechunkResult(int version, int chunkCount, int autoDrop, long suspect) {
    }

    /**
     * 保存整篇 md（在线编辑）：按页标记切页 → 页面级清洗 → 落新版本（append-only）→
     * 同步重分块（事务内删旧 chunk 重建，仍 PREVIEWING）。
     * 仅 PREVIEWING（展示门）允许；ACCEPTED 后悔通道已关闭。
     */
    @Transactional
    public RechunkResult saveMd(Long docId, String mdText, Long userId) {
        Document doc = requireDoc(docId);
        if (!Document.CURATE_PREVIEWING.equals(doc.getCurateStatus())) {
            throw new BizException("仅展示门阶段可编辑 md（当前状态 " + doc.getCurateStatus() + "）");
        }
        List<PageEntry> entries = splitPages(mdText);
        List<LlamaParseService.PageMarkdown> pages = entries.stream()
                .map(e -> new LlamaParseService.PageMarkdown(e.pageNum(), e.content()))
                .toList();
        if (pages.isEmpty()) {
            throw new BizException("清洗后的 md 为空，无法保存");
        }
        // 页面级规则重跑（人工编辑可能引入/遗留页眉页脚噪声）
        pages = documentCleanService.cleanPages(pages).pages();
        // 落新版本（append-only 版本行即审计）
        int version = curateRepository.maxVersion(docId) + 1;
        List<DocumentCurate> rows = new ArrayList<>();
        for (LlamaParseService.PageMarkdown p : pages) {
            DocumentCurate row = new DocumentCurate();
            row.setDocId(docId);
            row.setVersion(version);
            row.setPageNum(p.pageNumber());
            row.setContent(p.markdown());
            row.setUpdatedBy(userId == null ? 0L : userId);
            rows.add(row);
        }
        curateRepository.saveAll(rows);
        // 重分块：分块 + chunk 级清洗 + 事务内删旧插新
        List<ChunkPiece> pieces = parserService.chunkFromPages(pages);
        DocumentCleanService.ChunkCleanResult clean = documentCleanService.cleanChunks(pieces);
        boolean ok = parseTx.finalizeRechunk(docId, clean.kept(), clean.outcomes());
        if (!ok) {
            throw new BizException("文档已被删除，无法保存清洗结果");
        }
        long suspect = clean.outcomes().stream()
                .filter(o -> o.disposition() == DocumentCleanService.Disposition.SUSPECT).count();
        long autoDrop = clean.outcomes().stream()
                .filter(o -> o.disposition() == DocumentCleanService.Disposition.AUTO_DROP).count();
        recordLog(docId, "save_md",
                "重分块 v" + (version - 1), "v" + version + " " + pages.size() + " 页 / "
                        + clean.kept().size() + " chunk / AUTO-DROP " + autoDrop + " / SUSPECT " + suspect,
                userId);
        return new RechunkResult(version, clean.kept().size(), (int) autoDrop, suspect);
    }

    // ==================== 状态流转 ====================

    /** 接受：PREVIEWING → ACCEPTED（关闭 md 编辑/重分块后悔通道，开放 chunk 精修） */
    @Transactional
    public void accept(Long docId, Long userId) {
        Document doc = requireDoc(docId);
        if (!Document.CURATE_PREVIEWING.equals(doc.getCurateStatus())) {
            throw new BizException("仅展示门状态可接受（当前状态 " + doc.getCurateStatus() + "）");
        }
        doc.setCurateStatus(Document.CURATE_ACCEPTED);
        documentRepository.save(doc);
        recordLog(docId, "accept", Document.CURATE_PREVIEWING, Document.CURATE_ACCEPTED, userId);
    }

    /**
     * 确认完成：ACCEPTED → 清空 curateStatus（回到照旧）并触发统一向量化。
     * 向量化在事务提交后异步执行（AfterCommitExecutor）；未处置 SUSPECT 由 ingest 的
     * DEFER 过滤不进 ES（可在现有清洗复核页补处理）。
     */
    @Transactional
    public void confirm(Long docId, Long userId) {
        Document doc = requireDoc(docId);
        if (!Document.CURATE_ACCEPTED.equals(doc.getCurateStatus())) {
            throw new BizException("仅已接受状态可确认（当前状态 " + doc.getCurateStatus() + "）");
        }
        doc.setCurateStatus(null);
        documentRepository.save(doc);
        recordLog(docId, "confirm", Document.CURATE_ACCEPTED, "向量化", userId);
        // 事务提交后异步向量化：@Async 换独立线程执行，避免在 afterCommit 已提交事务上下文中
        // 同步 ingest 导致 chunk save 被静默丢弃（esId 不回填）
        afterCommitExecutor.runAfterCommit(() -> vectorIngestionService.ingestAsync(docId));
    }

    // ==================== chunk 级精修（ACCEPTED 后，确认前） ====================

    /** 编辑任意 chunk（SUSPECT 或普通）：仅改 MySQL + 审计，不触 ES（统一在 confirm 向量化） */
    @Transactional
    public ChunkReviewResponse editChunk(Long chunkId, String content, String title, Long userId) {
        if (content == null || content.trim().isEmpty()) {
            throw new BizException("编辑后内容不能为空");
        }
        Chunk chunk = requireChunk(chunkId);
        requireAccepted(chunk.getDocId());
        String before = chunk.getContent();
        chunk.setContent(content);
        chunk.setTitle(title);
        chunkRepository.save(chunk);
        recordChunkLog(chunk, "edit", before, content, userId);
        return ChunkReviewResponse.of(chunk, docName(chunk.getDocId()));
    }

    /** 删除 chunk（SUSPECT 或普通）：cleanStatus/status→FILTERED，记录保留（软删，不触 ES） */
    @Transactional
    public ChunkReviewResponse dropChunk(Long chunkId, Long userId) {
        Chunk chunk = requireChunk(chunkId);
        requireAccepted(chunk.getDocId());
        String before = chunk.getContent();
        chunk.setCleanStatus(ChunkReviewService.CLEAN_FILTERED);
        chunk.setStatus(ChunkStatus.FILTERED.value());
        chunkRepository.save(chunk);
        recordChunkLog(chunk, "drop", before, null, userId);
        return ChunkReviewResponse.of(chunk, docName(chunk.getDocId()));
    }

    /** 保留 SUSPECT（→KEEP）：确认前处置，非 SUSPECT 幂等返回 */
    @Transactional
    public ChunkReviewResponse keepChunk(Long chunkId, Long userId) {
        Chunk chunk = requireChunk(chunkId);
        requireAccepted(chunk.getDocId());
        if (!"SUSPECT".equals(chunk.getCleanStatus())) {
            return ChunkReviewResponse.of(chunk, docName(chunk.getDocId()));
        }
        chunk.setCleanStatus(ChunkReviewService.CLEAN_KEEP);
        chunkRepository.save(chunk);
        recordChunkLog(chunk, "keep", null, null, userId);
        return ChunkReviewResponse.of(chunk, docName(chunk.getDocId()));
    }

    /**
     * 精修：合并相邻 chunk（source 并入 target，保留 target id；确认前不触 ES）。
     * 合并后 target 置 SUSPECT（回到待审，confirm 后由复核页处置）；source 软删 FILTERED（记录保留）。
     * 约束：同文档、|seq差|=1、双方均未进 ES（esId 空且 EMBEDDING）、非 FILTERED、合并后内容非空。
     */
    @Transactional
    public ChunkReviewResponse mergeChunk(Long docId, Long sourceId, Long targetId, Long userId) {
        Chunk source = requireChunk(sourceId);
        Chunk target = requireChunk(targetId);
        if (!source.getDocId().equals(docId) || !target.getDocId().equals(docId)) {
            throw new BizException("分块不属于该文档");
        }
        requireAccepted(docId);
        if (sourceId.equals(targetId)) {
            throw new BizException("合并源与目标不能是同一分块");
        }
        if (Math.abs(source.getSeq() - target.getSeq()) != 1) {
            throw new BizException("仅支持合并相邻分块（当前 seq " + source.getSeq() + " / " + target.getSeq() + "）");
        }
        if (source.getEsId() != null || target.getEsId() != null
                || !ChunkStatus.EMBEDDING.is(source.getStatus()) || !ChunkStatus.EMBEDDING.is(target.getStatus())) {
            throw new BizException("仅未向量化的分块可合并（已进 ES/已索引的分块请先在清洗复核页处理）");
        }
        if (ChunkStatus.FILTERED.is(source.getStatus()) || ChunkStatus.FILTERED.is(target.getStatus())) {
            throw new BizException("已删除的分块不可参与合并");
        }
        // 按文档顺序拼接（seq 小者在先）
        Chunk first = source.getSeq() < target.getSeq() ? source : target;
        Chunk second = source.getSeq() < target.getSeq() ? target : source;
        String merged = first.getContent() + "\n" + second.getContent();
        if (merged.trim().isEmpty()) {
            throw new BizException("合并后内容不能为空");
        }
        String beforeTarget = target.getContent();
        String beforeSource = source.getContent();
        // 写目标（保留 target id/seq/title，pageNum 取 min，置 SUSPECT 待审，不动 esId/status，不触 ES）
        target.setContent(merged);
        target.setPageNum(Math.min(
                source.getPageNum() == null ? 0 : source.getPageNum(),
                target.getPageNum() == null ? 0 : target.getPageNum()));
        target.setCleanStatus(ChunkReviewService.CLEAN_SUSPECT);
        target.setCleanReason("人工合并（原 chunk " + source.getId() + "、" + target.getId() + "）");
        chunkRepository.save(target);
        // 源软删（记录保留，供历史引用"查看全文"兜底）
        source.setCleanStatus(ChunkReviewService.CLEAN_FILTERED);
        source.setStatus(ChunkStatus.FILTERED.value());
        chunkRepository.save(source);
        // 审计：merge（目标 before/after）+ merge-source（源 before/after=目标 id）
        recordChunkLog(target, "merge", beforeTarget, merged, userId);
        recordChunkLog(source, "merge-source", beforeSource, "并入 chunk " + target.getId(), userId);
        return ChunkReviewResponse.of(target, docName(target.getDocId()));
    }

    // ==================== 工具 ====================

    /** 页切分结果 */
    record PageEntry(int pageNum, String content) {
    }

    /**
     * 整篇 md → 按 {@code <!-- PAGE N -->} 切页。
     * 规则：首标记前的文本并入第 1 个标记页开头；标记 N 后到下一标记前的文本归页 N；
     * 尾段归最后标记页；整篇无标记 → 全部归第 1 页。段边缘换行剔除（页间以单 \n 拼接），
     * 空白页跳过（页号永 ≥ 1，与页面级清洗兼容）。
     */
    static List<PageEntry> splitPages(String mdText) {
        List<PageEntry> pages = new ArrayList<>();
        if (mdText == null || mdText.isBlank()) {
            return pages;
        }
        Matcher m = PAGE_MARK.matcher(mdText);
        List<int[]> marks = new ArrayList<>(); // {start, end, pageNum}
        while (m.find()) {
            marks.add(new int[]{m.start(), m.end(), Integer.parseInt(m.group(1))});
        }
        if (marks.isEmpty()) {
            addPage(pages, 1, mdText);
            return pages;
        }
        int firstPage = marks.get(0)[2];
        StringBuilder first = new StringBuilder();
        appendSegment(first, stripEdgeNewlines(mdText.substring(0, marks.get(0)[0])));
        for (int i = 0; i < marks.size(); i++) {
            int[] mark = marks.get(i);
            int segEnd = (i + 1 < marks.size()) ? marks.get(i + 1)[0] : mdText.length();
            String seg = stripEdgeNewlines(mdText.substring(mark[1], segEnd));
            if (i == 0) {
                appendSegment(first, seg);
                addPage(pages, firstPage, first.toString());
            } else {
                StringBuilder sb = new StringBuilder();
                appendSegment(sb, seg);
                addPage(pages, mark[2], sb.toString());
            }
        }
        return pages;
    }

    /** 剔除段边缘换行（\n/\r），保留段内结构 */
    private static String stripEdgeNewlines(String s) {
        if (s == null) {
            return "";
        }
        int start = 0;
        int end = s.length();
        while (start < end && (s.charAt(start) == '\n' || s.charAt(start) == '\r')) {
            start++;
        }
        while (end > start && (s.charAt(end - 1) == '\n' || s.charAt(end - 1) == '\r')) {
            end--;
        }
        return s.substring(start, end);
    }

    /** 追加段（非空时与前文以单 \n 分隔） */
    private static void appendSegment(StringBuilder sb, String seg) {
        if (seg.isEmpty()) {
            return;
        }
        if (sb.length() > 0) {
            sb.append('\n');
        }
        sb.append(seg);
    }

    /** 落页（空白页跳过） */
    private static void addPage(List<PageEntry> pages, int pageNum, String content) {
        if (content != null && !content.isBlank()) {
            pages.add(new PageEntry(pageNum, content));
        }
    }

    private void requireAccepted(Long docId) {
        Document doc = requireDoc(docId);
        if (!Document.CURATE_ACCEPTED.equals(doc.getCurateStatus())) {
            throw new BizException("仅已接受（确认前）可编辑 chunk（当前状态 " + doc.getCurateStatus() + "）");
        }
    }

    private Document requireDoc(Long docId) {
        return documentRepository.findById(docId)
                .orElseThrow(() -> new BizException("文档不存在: " + docId));
    }

    private Chunk requireChunk(Long chunkId) {
        return chunkRepository.findById(chunkId)
                .orElseThrow(() -> new BizException("chunk 不存在: " + chunkId));
    }

    private String docName(Long docId) {
        return documentRepository.findById(docId).map(Document::getFileName).orElse("");
    }

    /** 文档级动作审计（save_md / accept / confirm / edit_chunk / drop_chunk / keep_chunk） */
    private void recordLog(Long docId, String action, String before, String after, Long userId) {
        try {
            DocumentCurateLog entry = new DocumentCurateLog();
            entry.setDocId(docId);
            entry.setAction(action);
            entry.setBeforeSummary(truncate(before));
            entry.setAfterSummary(truncate(after));
            entry.setUserId(userId);
            curateLogRepository.save(entry);
        } catch (Exception e) {
            log.warn("策展审计写入失败（不影响主操作）docId={} action={}: {}", docId, action, e.getMessage());
        }
    }

    /** chunk 级内容留痕（与 ChunkReviewService 同表同语义） */
    private void recordChunkLog(Chunk chunk, String action, String before, String after, Long userId) {
        try {
            ChunkReviewLog entry = new ChunkReviewLog();
            entry.setChunkId(chunk.getId());
            entry.setDocId(chunk.getDocId());
            entry.setAction(action);
            entry.setBeforeContent(truncate(before));
            entry.setAfterContent(truncate(after));
            entry.setUserId(userId);
            reviewLogRepository.save(entry);
        } catch (Exception e) {
            log.warn("chunk 审计写入失败（不影响主操作）chunk={} action={}: {}", chunk.getId(), action, e.getMessage());
        }
    }

    private static String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() > 10000 ? s.substring(0, 10000) : s;
    }

    /** 供其他组件判断文档是否处于策展流程 */
    public static boolean isCurating(Document doc) {
        return doc != null && doc.getCurateStatus() != null
                && (Document.CURATE_PREVIEWING.equals(doc.getCurateStatus())
                || Document.CURATE_ACCEPTED.equals(doc.getCurateStatus()));
    }
}
