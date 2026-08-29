package com.ai.konwledgerepo.service.document;

import com.ai.konwledgerepo.common.BizException;
import com.ai.konwledgerepo.config.props.SeuDocumentProperties;
import com.ai.konwledgerepo.entity.Document;
import com.ai.konwledgerepo.service.knowledgebase.WorkspaceIdResolver;
import com.ai.konwledgerepo.tracing.QaTracing;
import com.ai.konwledgerepo.tracing.TokenAccumulator;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
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
 * 文档解析服务（分发）：按类型（txt/md/pdf/docx/pptx）提取文本并分块。
 * txt/md 使用标题感知分块（ChunkSplitter，原逻辑不变）；
 * pdf/docx 在启用 LlamaParse 时走云端转 Markdown + 递归分块（RecursiveChunkSplitter），
 * pdf 失败/未配置时回退 {@link PdfParseService}（PDFBox 纯文本 + 按需识图）；
 * 缺页补全见 {@link VisionPageFiller}；pptx 复用 {@link PptxParserService}。
 * 每次解析创建一条 OpenTelemetry trace（document/parse），识图调用按 vision 类统计 token。
 */
@Service
public class DocumentParserService {

    private static final Logger log = LoggerFactory.getLogger(DocumentParserService.class);

    private final LlamaParseService llamaParseService;
    private final PptxParserService pptxParserService;
    private final WorkspaceIdResolver workspaceIdResolver;
    private final PdfParseService pdfParseService;
    private final VisionPageFiller visionPageFiller;
    private final QaTracing qaTracing;
    private final int chunkSize;
    private final int chunkOverlap;
    private final boolean fillMissingPages;

    public DocumentParserService(LlamaParseService llamaParseService,
                                 PptxParserService pptxParserService,
                                 WorkspaceIdResolver workspaceIdResolver,
                                 PdfParseService pdfParseService,
                                 VisionPageFiller visionPageFiller,
                                 QaTracing qaTracing,
                                 SeuDocumentProperties docProps) {
        this.llamaParseService = llamaParseService;
        this.pptxParserService = pptxParserService;
        this.workspaceIdResolver = workspaceIdResolver;
        this.pdfParseService = pdfParseService;
        this.visionPageFiller = visionPageFiller;
        this.qaTracing = qaTracing;
        this.chunkSize = docProps.chunkSize();
        this.chunkOverlap = docProps.chunkOverlap();
        this.fillMissingPages = docProps.llamaparse().fillMissingPages();
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
            return doParse(doc, workspaceId);
        } catch (Exception e) {
            root.recordException(e);
            throw e;
        } finally {
            TokenAccumulator.flushToSpan(root);
            root.end();
        }
    }

    private List<ChunkPiece> doParse(Document doc, Long workspaceId) {
        try {
            Path path = Path.of(doc.getFilePath());
            return switch (doc.getFileType().toLowerCase()) {
                case "txt", "md" -> parseText(path);
                case "pdf" -> parsePdfWithLlamaParseFallback(path, doc.getFileName(), workspaceId);
                case "docx" -> parseDocx(path, doc.getFileName());
                case "pptx" -> pptxParserService.parse(path, doc.getFileName(), workspaceId);
                default -> throw new BizException("不支持的文件类型: " + doc.getFileType());
            };
        } catch (IOException e) {
            throw new BizException("文档解析失败: " + e.getMessage());
        }
    }

    private List<ChunkPiece> parseText(Path path) throws IOException {
        String content = Files.readString(path, StandardCharsets.UTF_8);
        return ChunkSplitter.split(content, 0, chunkSize, chunkOverlap);
    }

    /**
     * PDF 解析：优先 LlamaParse 云端转 Markdown + 递归分块（缺页由识图模型补全）；
     * 未启用/调用失败时回退 PDFBox 纯文本解析（含按需识图）。
     */
    private List<ChunkPiece> parsePdfWithLlamaParseFallback(Path path, String fileName, Long workspaceId) throws IOException {
        if (llamaParseService.isConfigured()) {
            try {
                return parseWithLlamaParse(path, fileName, workspaceId);
            } catch (Exception e) {
                log.warn("LlamaParse 解析 PDF 失败，回退 PDFBox: {}", e.getMessage());
            }
        }
        return pdfParseService.parse(path, workspaceId);
    }

    /** DOCX 解析：必须启用 LlamaParse（无本地回退） */
    private List<ChunkPiece> parseDocx(Path path, String fileName) {
        if (!llamaParseService.isConfigured()) {
            throw new BizException("DOCX 解析需要启用 LlamaParse（seuknowledge.document.llamaparse.enabled 且配置 API Key）");
        }
        return parseWithLlamaParse(path, fileName, null);
    }

    /**
     * LlamaParse 逐页转 Markdown 后递归分块：先检测缺页并用识图模型补全（按页码并入 md 流），
     * 再逐页分块，跨页 carry 延续（页尾重叠文本并入下一页首个片段，标题开头时丢弃），跨页标题继承。
     */
    private List<ChunkPiece> parseWithLlamaParse(Path path, String fileName, Long workspaceId) {
        // needScreenshots=false：PDF 缺页补全用 PDFBox 渲染（不读截图），不下载 LlamaParse 整页截图
        List<LlamaParseService.PageMarkdown> pages = llamaParseService.parseToMarkdown(path, fileName, false);
        if (fillMissingPages) {
            pages = visionPageFiller.fill(path, pages, workspaceId);
        }
        // 复制为可变列表再排序（调用方可能返回不可变列表，如测试 mock 的 List.of）
        List<LlamaParseService.PageMarkdown> ordered = new ArrayList<>(pages);
        ordered.sort(Comparator.comparingInt(LlamaParseService.PageMarkdown::pageNumber));
        List<ChunkPiece> pieces = new ArrayList<>();
        String[] carry = new String[1];
        // 跨页标题祖先栈（section_path）继承：上一页末栈传给下一页无标题块
        List<String>[] inheritStack = new List[1];
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
}
