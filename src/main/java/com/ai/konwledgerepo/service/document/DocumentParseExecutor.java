package com.ai.konwledgerepo.service.document;

import com.ai.konwledgerepo.common.BizException;
import com.ai.konwledgerepo.common.RedisKeys;
import com.ai.konwledgerepo.common.TaskLock;
import com.ai.konwledgerepo.entity.Document;
import com.ai.konwledgerepo.service.vector.VectorIngestionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * 文档异步解析执行器：提取文本 → 分块 → 写入 MySQL chunk → 向量化入 ES。
 * <p>
 * 初洗门分支：curateRequired=true 且类型为 LlamaParse 产物（html/pdf/docx）时，解析到逐页 md
 * 落库（v1）→ 分块 → 落 chunk 后停在初洗（PREVIEWING，不向量化），等待人工决断
 * （编辑 md 重分块 / 接受后精修 / 确认后统一向量化）；其余照旧自动链路。
 * <p>
 * 并发安全增强（F-1 / F-2 / F-7）：
 * <ul>
 *   <li>Redis SETNX in-flight guard（taskLock）：防止同一文档被两次解析（解析间互斥）。</li>
 *   <li>DB 悲观锁状态机（DocumentParseTx）：startParse 以 FOR UPDATE 从 PENDING 原子迁入
 *       PARSING；finalize* 以 FOR UPDATE 重读行，行不存在（已被删除）→ 静默中止，不产生孤儿 chunk。</li>
 *   <li>chunk 批量插入与状态更新在同一事务内完成（saveAll），避免 delete 与解析的高并发交错。</li>
 *   <li>向量化前（ingest）已由 VectorIngestionService 自身重校验文档存在性，ES 侧无孤儿。</li>
 *   <li>零产出守卫（{@link #requireAnyChunk}）：分块结果为空按解析失败处理，不落 SUCCESS，
 *       避免"SUCCESS 但检索不到"与初洗 md/分块错位。</li>
 * </ul>
 * 独立 bean 承载 {@code @Async}，避免从 {@link DocumentService} 同 bean 内直接调用导致
 * 代理失效（自调用不生效）——修复"上传接口同步阻塞到解析完成、事务长时间不提交"问题。
 */
@Service
public class DocumentParseExecutor {

    private static final Logger log = LoggerFactory.getLogger(DocumentParseExecutor.class);
    private static final Duration PARSE_LOCK_TTL = Duration.ofMinutes(30);

    private final TaskLock taskLock;
    private final DocumentParseTx parseTx;
    private final DocumentParserService parserService;
    private final DocumentCleanService documentCleanService;
    private final DocumentCurateService curateService;
    private final VectorIngestionService vectorIngestionService;

    public DocumentParseExecutor(TaskLock taskLock,
                                 DocumentParseTx parseTx,
                                 DocumentParserService parserService,
                                 DocumentCleanService documentCleanService,
                                 DocumentCurateService curateService,
                                 VectorIngestionService vectorIngestionService) {
        this.taskLock = taskLock;
        this.parseTx = parseTx;
        this.parserService = parserService;
        this.documentCleanService = documentCleanService;
        this.curateService = curateService;
        this.vectorIngestionService = vectorIngestionService;
    }

    /**
     * 异步解析：文本提取 → 分块 → 落库 → 向量化。
     * 由 DocumentService 在事务提交后触发，确保本线程能读到已提交的文档数据。
     *
     * @param reuseCache 用户是否选择复用解析缓存（@Transient，startParse 重查后由本方法回填，
     *                   供 DocumentParserService 读取）
     */
    @Async
    public void parseAsync(Long docId, boolean reuseCache) {
        // ---- F-2 in-flight guard：防双解析（worker×worker） ----
        if (!taskLock.tryAcquire(RedisKeys.docParse(docId), PARSE_LOCK_TTL)) {
            log.info("文档 {} 解析已在进行，跳过本次触发", docId);
            return;
        }
        try {
            // ---- F-2 状态机：PENDING → PARSING（悲观锁 + 状态校验） ----
            Optional<Document> docOpt = parseTx.startParse(docId);
            if (docOpt.isEmpty()) {
                return; // 文档不存在 / 已被并发解析 / 状态非 PENDING
            }
            Document doc = docOpt.get();
            doc.setReuseCache(reuseCache);
            if (Boolean.TRUE.equals(doc.getCurateRequired())
                    && parserService.supportsLlamaParseCuration(doc.getFileType())) {
                parseGated(doc);
            } else {
                parseAuto(doc);
            }
        } catch (Exception e) {
            log.error("文档 {} 解析失败", docId, e);
            // ---- F-1 失败路径：事务内重读 + FAILED（行不存在则静默返回） ----
            parseTx.finalizeFailure(docId, e.getMessage());
        } finally {
            taskLock.release(RedisKeys.docParse(docId));
        }
    }

    /** 初洗门路径：LlamaParse 逐页 md → 落库 v1（已有旧版本时原子替换，md 与 chunk 对齐）→ 分块 → 落 chunk 停在初洗（不向量化） */
    private void parseGated(Document doc) {
        List<LlamaParseService.PageMarkdown> pages = parserService.parseToPages(doc);
        // retry 重解析：本轮新 pages 整体顶替旧初洗 md（落 v1）；解析失败/空结果不进此处，旧 md 保留
        curateService.saveInitialMd(doc.getId(), pages, doc.getCreatedBy());
        List<ChunkPiece> pieces = parserService.chunkFromPages(pages);
        // ---- P1 chunk 级清洗：E 保护 → A 碎片 → B 图题 → C 重复（处置由配置名单决定） ----
        DocumentCleanService.ChunkCleanResult clean = documentCleanService.cleanChunks(pieces);
        requireAnyChunk(clean);
        // ---- 收尾：事务内重读 + chunk 批量写入 + SUCCESS + PREVIEWING（不 ingest，等人工决断） ----
        boolean ok = parseTx.finalizeSuccessGated(doc.getId(), clean.kept(), clean.outcomes());
        if (ok) {
            log.info("文档 {} 解析完成并进入初洗（清洗前 {}，AUTO-DROP {}，SUSPECT {}），等待人工决断",
                    doc.getFileName(), pieces.size(),
                    clean.outcomes().stream().filter(o -> o.disposition() == DocumentCleanService.Disposition.AUTO_DROP).count(),
                    clean.outcomes().stream().filter(o -> o.disposition() == DocumentCleanService.Disposition.SUSPECT).count());
        }
    }

    /** 照旧自动路径：解析 → 分块 → 落库 → 向量化 */
    private void parseAuto(Document doc) {
        // ---- 长任务：文本解析（无事务，不占用连接） ----
        List<ChunkPiece> pieces = parserService.parse(doc);
        // ---- P1 chunk 级清洗：E 保护 → A 碎片 → B 图题 → C 重复（处置由配置名单决定） ----
        DocumentCleanService.ChunkCleanResult clean = documentCleanService.cleanChunks(pieces);
        requireAnyChunk(clean);
        // ---- F-1 收尾：事务内重读 + chunk 批量写入 + SUCCESS（行不存在则静默中止） ----
        boolean ok = parseTx.finalizeSuccess(doc.getId(), clean.kept(), clean.outcomes());
        if (ok) {
            // 向量化（ingest 内部会重校验文档存在性，ES 无孤儿；FILTERED 的 chunk 天然被跳过）
            vectorIngestionService.ingest(doc.getId());
        }
        log.info("文档 {} 解析完成，{} 个 chunk（清洗前 {}，AUTO-DROP {}，SUSPECT {}）",
                doc.getFileName(), clean.kept().size() + clean.outcomes().size(),
                pieces.size(),
                clean.outcomes().stream().filter(o -> o.disposition() == DocumentCleanService.Disposition.AUTO_DROP).count(),
                clean.outcomes().stream().filter(o -> o.disposition() == DocumentCleanService.Disposition.SUSPECT).count());
    }

    /**
     * 零产出守卫：本轮既没有保留块也没有被清洗掉的块（即分块结果为空）时判为解析失败。
     * <p>
     * 为什么必须拦：{@code parseToPages} → {@code pagesFromLlamaParse} 无空结果守卫，页面级清洗把
     * 所有页剥成噪声时返回空页列表；此时初洗门路径的 {@code saveInitialMd} 会因空守卫<b>保留旧 md</b>，
     * 而 {@code finalize} 仍无条件落 0 chunk 并置 SUCCESS + PREVIEWING——初洗编辑器里"满屏旧 md、
     * 分块数 0"的错位就是这么来的（反向于已修复的 retry 错位）。抛错后走 {@code finalizeFailure}：
     * 文档 FAILED + 明确 errorMsg，旧初洗 md 原样保留供人工救济，且不再滞留初洗队列。
     */
    private static void requireAnyChunk(DocumentCleanService.ChunkCleanResult clean) {
        if (clean.kept().isEmpty() && clean.outcomes().isEmpty()) {
            throw new BizException("解析未产出任何可用分块（内容可能全为空白页/噪声页，或云端返回空结果）");
        }
    }

}
