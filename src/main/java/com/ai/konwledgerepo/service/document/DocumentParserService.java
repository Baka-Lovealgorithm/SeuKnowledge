package com.ai.konwledgerepo.service.document;

import com.ai.konwledgerepo.common.BizException;
import com.ai.konwledgerepo.common.Texts;
import com.ai.konwledgerepo.config.props.SeuDocumentProperties;
import com.ai.konwledgerepo.entity.Document;
import com.ai.konwledgerepo.service.knowledgebase.WorkspaceIdResolver;
import com.ai.konwledgerepo.service.storage.DocumentBlobService;
import com.ai.konwledgerepo.service.storage.MaterializedFile;
import com.ai.konwledgerepo.tracing.QaTracing;
import com.ai.konwledgerepo.tracing.TokenAccumulator;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 文档解析服务（分发）：按类型（txt/md/html/pdf/docx/pptx/xlsx/xls）提取文本并分块。
 * txt 使用标题感知分块（ChunkSplitter，原逻辑不变）；
 * md 直传使用递归分块（RecursiveChunkSplitter，代码围栏原子/行组 + 表格 A+B 分块 + forward-fill，与 LlamaParse md 产物同链路）；
 * html 默认走 LlamaParse 云端转 Markdown + 递归分块（RecursiveChunkSplitter），可显式启用本地 Jsoup 备用解析；
 * docx 必须走 LlamaParse 云端转 Markdown + 递归分块；
 * pdf 在启用 LlamaParse 时走云端转 Markdown + 递归分块，
 * pdf 失败/未配置时回退 {@link PdfParseService}（PDFBox 纯文本 + 按需识图）；
 * 缺页补全见 {@link VisionPageFiller}；pptx 复用 {@link PptxParserService}；
 * xlsx/xls 本地 POI 解析（{@link ExcelParserService}，数据不出本地，复用表格 A+B 分块）。
 * 每次解析创建一条 OpenTelemetry trace（document/parse），识图调用按 vision 类统计 token。
 */
@Service
public class DocumentParserService {

    private static final Logger log = LoggerFactory.getLogger(DocumentParserService.class);

    private final LlamaParseService llamaParseService;
    private final HtmlParserService htmlParserService;
    private final PptxParserService pptxParserService;
    private final ExcelParserService excelParserService;
    private final WorkspaceIdResolver workspaceIdResolver;
    private final PdfParseService pdfParseService;
    private final VisionPageFiller visionPageFiller;
    private final ParseCacheService parseCacheService;
    private final DocumentCleanService documentCleanService;
    private final DocumentBlobService blobService;
    private final QaTracing qaTracing;
    private final int chunkSize;
    private final int chunkOverlap;
    private final boolean fillMissingPages;
    private final boolean localHtmlParserEnabled;

    public DocumentParserService(LlamaParseService llamaParseService,
                                 HtmlParserService htmlParserService,
                                 PptxParserService pptxParserService,
                                 ExcelParserService excelParserService,
                                 WorkspaceIdResolver workspaceIdResolver,
                                 PdfParseService pdfParseService,
                                 VisionPageFiller visionPageFiller,
                                 ParseCacheService parseCacheService,
                                 DocumentCleanService documentCleanService,
                                 DocumentBlobService blobService,
                                 QaTracing qaTracing,
                                 SeuDocumentProperties docProps,
                                 @Value("${seuknowledge.document.local-html-parser-enabled:false}")
                                 boolean localHtmlParserEnabled) {
        this.llamaParseService = llamaParseService;
        this.htmlParserService = htmlParserService;
        this.pptxParserService = pptxParserService;
        this.excelParserService = excelParserService;
        this.workspaceIdResolver = workspaceIdResolver;
        this.pdfParseService = pdfParseService;
        this.visionPageFiller = visionPageFiller;
        this.parseCacheService = parseCacheService;
        this.documentCleanService = documentCleanService;
        this.blobService = blobService;
        this.qaTracing = qaTracing;
        this.chunkSize = docProps.chunkSize();
        this.chunkOverlap = docProps.chunkOverlap();
        this.fillMissingPages = docProps.llamaparse().fillMissingPages();
        this.localHtmlParserEnabled = localHtmlParserEnabled;
    }

    /**
     * 解析文档为分块片段列表。
     *
     * @return 每个片段带页码（pdf 有效页码，txt/md 为 0）
     */
    public List<ChunkPiece> parse(Document doc) {
        Span root = qaTracing.begin("document/parse");
        root.setAttribute("doc.id", doc.getId() == null ? 0 : doc.getId());
        root.setAttribute("doc.name", doc.getFileName());
        root.setAttribute("langfuse.trace.name", "文档解析: " + doc.getFileName());
        root.setAttribute("langfuse.span.type", "TASK");
        TokenAccumulator.begin();
        try (Scope scope = root.makeCurrent()) {
            // 按知识库归属解析工作空间（识图模型按空间解析）
            Long workspaceId = workspaceIdResolver.resolve(doc.getKbId());
            return doParse(doc, workspaceId, doc.isReuseCache());
        } catch (Exception e) {
            root.recordException(e);
            throw e;
        } finally {
            TokenAccumulator.flushToSpan(root);
            root.end();
        }
    }

    private List<ChunkPiece> doParse(Document doc, Long workspaceId, boolean reuseCache) {
        Long docId = doc.getId();
        // 物化：local 后端直接返回真实文件（不删），minio 后端先下载到临时副本（close 时删）。
        // 下游解析器（POI/PDFBox/Jsoup/LlamaParse 上传）全部按内容嗅探，且路径不逃逸本调用栈
        // （VisionPageFiller 内部线程池 join 后才返回），故 try-with-resources 是安全的清理时机。
        try (MaterializedFile source = blobService.materializeOriginal(doc)) {
            Path path = source.path();
            return switch (doc.getFileType().toLowerCase()) {
                case "txt" -> parseText(path);
                case "md" -> parseMarkdown(path);
                case "html" -> parseHtml(path, docId, doc.getKbId(), doc.getFileName(), reuseCache);
                case "pdf" -> parsePdfWithLlamaParseFallback(path, docId, doc.getKbId(), doc.getFileName(),
                        workspaceId, reuseCache);
                case "docx" -> parseLlamaParseRequired(path, docId, doc.getKbId(), doc.getFileName(), reuseCache, "DOCX");
                case "pptx" -> pptxParserService.parse(path, docId, doc.getKbId(), doc.getFileName(), workspaceId);
                case "xlsx", "xls" -> excelParserService.parse(path, doc.getFileName());
                default -> throw new BizException("不支持的文件类型: " + doc.getFileType());
            };
        } catch (IOException e) {
            throw new BizException("文档解析失败: " + e.getMessage());
        }
    }

    /** HTML 默认使用 LlamaParse；仅显式开启开关时使用本地 Jsoup 备用解析。 */
    private List<ChunkPiece> parseHtml(Path path, Long docId, Long kbId, String fileName, boolean reuseCache) {
        if (localHtmlParserEnabled) {
            log.info("本地 HTML 备用解析已启用，跳过 LlamaParse: {}", fileName);
            return htmlParserService.parse(path, chunkSize, chunkOverlap);
        }
        return parseLlamaParseRequired(path, docId, kbId, fileName, reuseCache, "HTML");
    }

    /** 当前配置下，文件类型是否支持 LlamaParse 的逐页 Markdown 初洗流程。 */
    boolean supportsLlamaParseCuration(String fileType) {
        if (fileType == null) {
            return false;
        }
        String type = fileType.toLowerCase();
        return ("html".equals(type) && !localHtmlParserEnabled)
                || "pdf".equals(type)
                || "docx".equals(type);
    }

    /**
     * 初洗门专用：解析到逐页 markdown（页面级清洗 + 缺页补全后），不进入分块。
     * 仅支持会走 LlamaParse 的文档类型（html/pdf/docx，md 产物）；txt/md/pptx/xlsx 不支持
     * 初洗门（无逐页 md），调用方应据 fileType 判断后回退照旧链路。
     *
     * @throws BizException LlamaParse 未启用或类型不支持初洗门
     */
    public List<LlamaParseService.PageMarkdown> parseToPages(Document doc) {
        Long workspaceId = workspaceIdResolver.resolve(doc.getKbId());
        String type = doc.getFileType().toLowerCase();
        if (!"html".equals(type) && !"pdf".equals(type) && !"docx".equals(type)) {
            throw new BizException("初洗门仅支持 HTML/PDF/DOCX（LlamaParse md 产物）");
        }
        if (!llamaParseService.isConfigured()) {
            throw new BizException("初洗门需要启用 LlamaParse（seuknowledge.document.llamaparse.enabled 且配置 API Key）");
        }
        // 初洗门路径不在本方法内镜像 md：权威落库点是 DocumentCurateService.saveInitialMd（清洗后的 pages），
        // 避免「原始 md vs 清洗后 md」双写；此处只负责读出文件。
        try (MaterializedFile source = blobService.materializeOriginal(doc)) {
            return pagesFromLlamaParse(source.path(), doc.getFileName(), workspaceId,
                    doc.isReuseCache(), "pdf".equals(type));
        }
    }

    /**
     * 逐页 markdown → 分块片段（跨页 carry 延续，跨页标题继承）。
     * 初始解析与初洗重分块共用：重分块时由调用方先做页面级清洗再传入。
     */
    public List<ChunkPiece> chunkFromPages(List<LlamaParseService.PageMarkdown> pages) {
        // 复制为可变列表再排序（调用方可能返回不可变列表，如测试 mock 的 List.of）
        List<LlamaParseService.PageMarkdown> ordered = new ArrayList<>(pages);
        ordered.sort(Comparator.comparingInt(LlamaParseService.PageMarkdown::pageNumber));
        List<ChunkPiece> pieces = new ArrayList<>();
        String[] carry = new String[1];
        // 跨页标题祖先栈（section_path）继承：上一页末栈传给下一页无标题块
        List<Headings.StackEntry>[] inheritStack = new List[1];
        for (LlamaParseService.PageMarkdown page : ordered) {
            if (page.markdown() == null || page.markdown().isBlank()) {
                continue;
            }
            RecursiveChunkSplitter.SplitResult sr = RecursiveChunkSplitter.splitWithCarry(
                    page.markdown(), page.pageNumber(), chunkSize, chunkOverlap, carry[0], inheritStack[0]);
            pieces.addAll(sr.pieces());
            carry[0] = sr.carryOut().isEmpty() ? null : sr.carryOut();
            if (sr.lastStack() != null && !sr.lastStack().isEmpty()) {
                inheritStack[0] = sr.lastStack();
            }
        }
        return pieces;
    }

    private List<ChunkPiece> parseText(Path path) throws IOException {
        String content = Files.readString(path, StandardCharsets.UTF_8);
        return ChunkSplitter.split(content, 0, chunkSize, chunkOverlap);
    }

    /** md 直传：整篇按 Markdown 递归分块（代码围栏原子/行组 + 表格 A+B 原子/行组 + forward-fill），与 LlamaParse md 产物同链路 */
    private List<ChunkPiece> parseMarkdown(Path path) throws IOException {
        String content = Files.readString(path, StandardCharsets.UTF_8);
        return RecursiveChunkSplitter.split(content, 0, chunkSize, chunkOverlap);
    }

    /**
     * PDF 解析：优先 LlamaParse 云端转 Markdown + 递归分块（缺页由识图模型补全）；
     * 未启用/调用失败时回退 PDFBox 纯文本解析（含按需识图）。
     *
     * @param docId      用于把 LlamaParse 产物 md 镜像到对象存储（回退 PDFBox 时不产生 md，故不镜像）
     * @param kbId       md 镜像的落点前缀（{wsId}/{kbId}/derived/md/），由 {@link DocumentBlobService} 解析空间
     * @param reuseCache 用户是否选择复用解析缓存（同内容文件跳过 LlamaParse）
     */
    private List<ChunkPiece> parsePdfWithLlamaParseFallback(Path path, Long docId, Long kbId, String fileName,
                                                            Long workspaceId, boolean reuseCache) throws IOException {
        if (llamaParseService.isConfigured()) {
            try {
                return parseWithLlamaParse(path, docId, kbId, fileName, workspaceId, reuseCache, true);
            } catch (Exception e) {
                log.warn("LlamaParse 解析 PDF 失败，回退 PDFBox: {}", e.getMessage());
            }
        }
        return pdfParseService.parse(path, workspaceId);
    }

    /** HTML/DOCX 解析：必须启用 LlamaParse（无本地回退）。 */
    private List<ChunkPiece> parseLlamaParseRequired(Path path, Long docId, Long kbId, String fileName,
                                                     boolean reuseCache, String fileType) {
        if (!llamaParseService.isConfigured()) {
            throw new BizException(fileType + " 解析需要启用 LlamaParse（seuknowledge.document.llamaparse.enabled 且配置 API Key）");
        }
        return parseWithLlamaParse(path, docId, kbId, fileName, null, reuseCache, false);
    }

    /**
     * LlamaParse 逐页转 Markdown 后递归分块：先检测缺页并用识图模型补全（按页码并入 md 流），
     * 再逐页分块，跨页 carry 延续（页尾重叠文本并入下一页首个片段，标题开头时丢弃），跨页标题继承。
     * <p>
     * 同时把逐页 md 镜像到对象存储（宽松模式：失败只 WARN）——这是「自动解析路径」的 md 写点，
     * 初洗门路径由 {@code DocumentCurateService.saveInitialMd} 负责，两者不重叠。
     *
     * @param reuseCache 用户是否选择复用解析缓存：true 时按文件哈希查缓存，命中跳过 LlamaParse
     *                   复用逐页 markdown；未命中/未选择则全量 LlamaParse 解析并写入缓存。
     */
    private List<ChunkPiece> parseWithLlamaParse(Path path, Long docId, Long kbId, String fileName, Long workspaceId,
                                                 boolean reuseCache, boolean fillPdfMissingPages) {
        List<LlamaParseService.PageMarkdown> pages =
                pagesFromLlamaParse(path, fileName, workspaceId, reuseCache, fillPdfMissingPages);
        if (docId != null) {
            blobService.putMdQuietly(kbId, docId, CurateMdText.assemblePages(pages));
        }
        return chunkFromPages(pages);
    }

    /**
     * LlamaParse 逐页转 Markdown（页面级清洗 + 缺页补全 + 排序），不进入分块。
     * 供初始解析（parseWithLlamaParse）与初洗门（parseToPages）共用。
     */
    private List<LlamaParseService.PageMarkdown> pagesFromLlamaParse(Path path, String fileName, Long workspaceId,
                                                                     boolean reuseCache,
                                                                     boolean fillPdfMissingPages) {
        List<LlamaParseService.PageMarkdown> pages;
        if (reuseCache) {
            pages = resolveFromCacheOrParse(path, fileName);
        } else {
            // 用户未选择复用：全量解析（仍写缓存，为将来复用做准备）
            pages = llamaParseService.parseToMarkdown(path, fileName, false);
            writeCache(path, fileName, pages);
        }
        // P1 页面级清洗：剥离页眉页脚行 + 删首尾噪声页（在 fill 之前，避免为封面/目录/空白页浪费识图调用）
        DocumentCleanService.PageCleanResult clean = documentCleanService.cleanPages(pages);
        pages = clean.pages();
        if (fillMissingPages && fillPdfMissingPages) {
            pages = visionPageFiller.fill(path, pages, workspaceId);
        }
        return pages;
    }

    /**
     * 用户选择复用时解析：按文件哈希查缓存，命中复用逐页 markdown；未命中全量 LlamaParse 并写缓存。
     */
    private List<LlamaParseService.PageMarkdown> resolveFromCacheOrParse(Path path, String fileName) {
        String hash = parseCacheService.sha256(path);
        java.util.Optional<List<LlamaParseService.PageMarkdown>> cached = parseCacheService.get(hash);
        if (cached.isPresent()) {
            List<LlamaParseService.PageMarkdown> pages = cached.get();
            log.info("文件级缓存命中（hash={}）跳过 LlamaParse: {} 共 {} 页",
                    shortHash(hash), fileName, pages.size());
            // 导出目录可能被清理；缓存命中时也恢复原始 Markdown 产物。
            llamaParseService.exportMarkdown(path, fileName, pages);
            return pages;
        }
        log.info("文件级缓存未命中（hash={}），全量 LlamaParse 解析: {}", shortHash(hash), fileName);
        List<LlamaParseService.PageMarkdown> pages = llamaParseService.parseToMarkdown(path, fileName, false);
        writeCache(path, fileName, pages);
        return pages;
    }

    /** 写入解析缓存（内部 try/catch，失败不影响解析） */
    private void writeCache(Path path, String fileName, List<LlamaParseService.PageMarkdown> pages) {
        try {
            String fileType = extensionOf(fileName);
            parseCacheService.put(parseCacheService.sha256(path), fileName, fileType, pages);
        } catch (Exception e) {
            log.warn("解析缓存写入跳过: {}", e.getMessage());
        }
    }

    private static String extensionOf(String fileName) {
        if (fileName == null || !fileName.contains(".")) {
            return "";
        }
        return fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase();
    }

    private static String shortHash(String hash) {
        return Texts.shortHash(hash);
    }
}
