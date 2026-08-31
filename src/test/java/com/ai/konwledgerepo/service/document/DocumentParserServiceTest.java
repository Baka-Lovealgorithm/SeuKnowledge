package com.ai.konwledgerepo.service.document;

import com.ai.konwledgerepo.common.BizException;
import com.ai.konwledgerepo.config.props.SeuDocumentProperties;
import com.ai.konwledgerepo.entity.Document;
import com.ai.konwledgerepo.entity.KnowledgeBase;
import com.ai.konwledgerepo.service.knowledgebase.WorkspaceIdResolver;
import com.ai.konwledgerepo.tracing.QaTracing;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentParserServiceTest {

    @TempDir
    Path tempDir;

    private VisionOcrService visionMock;

    /** 构造解析配置：visionParsing / visionParallel / fillMissingPages 可配，其余取测试固定值（minText=10, dpi=100, chunk=800/120, auto=true） */
    private static SeuDocumentProperties docProps(boolean visionParsing, int visionParallel, boolean fillMissingPages) {
        return new SeuDocumentProperties(visionParsing, true, 10, 100, visionParallel, 800, 120,
                new SeuDocumentProperties.LlamaParse(false, "", "https://api.cloud.llamaindex.ai",
                        "cost_effective", "latest", "ch_sim", 5, 900, "", true, fillMissingPages, null), SeuDocumentProperties.Clean.defaults());
    }

    /** 组装解析器：PdfParseService / VisionPageFiller 用真实实例（复用同一批 mock 与配置） */
    private static DocumentParserService build(VisionOcrService vision, LlamaParseService llama, PptxParserService pptx,
                                               ExcelParserService excel, WorkspaceIdResolver resolver, QaTracing tracing,
                                               SeuDocumentProperties props, Executor executor) {
        PdfParseService pdfService = new PdfParseService(vision, tracing, props, executor);
        VisionPageFiller filler = new VisionPageFiller(llama, vision, tracing, props, executor);
        DocumentCleanService clean = mock(DocumentCleanService.class);
        // 页面级清洗默认透传：pages 原样返回（页面级规则单独在 DocumentCleanServiceTest 覆盖）
        when(clean.cleanPages(any())).thenAnswer(inv ->
                new DocumentCleanService.PageCleanResult(inv.getArgument(0), List.of()));
        return new DocumentParserService(llama, pptx, excel, resolver, pdfService, filler,
                mock(ParseCacheService.class), clean, tracing, props);
    }

    /** 测试中关闭识图解析与追踪，保持纯文本分块行为 */
    private DocumentParserService parser() {
        QaTracing noopTracing = QaTracing.disabled();
        return build(mock(VisionOcrService.class), mock(LlamaParseService.class),
                mock(PptxParserService.class), mock(ExcelParserService.class), mock(WorkspaceIdResolver.class),
                noopTracing, docProps(false, 1, false), null);
    }

    /**
     * 开启识图 + 按需识图（auto）的解析器：mock 识图模型可用，知识库归属工作空间 1。
     * chunk-size=800 / overlap=120，vision-min-text=10，vision-parallel=1（串行路径，便于断言识图调用）。
     */
    private DocumentParserService visionParser() {
        QaTracing noopTracing = QaTracing.disabled();
        visionMock = mock(VisionOcrService.class);
        when(visionMock.isConfigured(any())).thenReturn(true);
        WorkspaceIdResolver resolver = mock(WorkspaceIdResolver.class);
        when(resolver.resolve(1L)).thenReturn(1L);
        return build(visionMock, mock(LlamaParseService.class),
                mock(PptxParserService.class), mock(ExcelParserService.class), resolver, noopTracing, docProps(true, 1, false), null);
    }

    /** 并行识图解析器：parallel=3 + 真实固定线程池，mock describeParallel 返回识别结果 */
    private DocumentParserService parallelVisionParser(Executor executor) {
        QaTracing noopTracing = QaTracing.disabled();
        visionMock = mock(VisionOcrService.class);
        when(visionMock.isConfigured(any())).thenReturn(true);
        WorkspaceIdResolver resolver = mock(WorkspaceIdResolver.class);
        when(resolver.resolve(1L)).thenReturn(1L);
        return build(visionMock, mock(LlamaParseService.class),
                mock(PptxParserService.class), mock(ExcelParserService.class), resolver, noopTracing, docProps(true, 3, false), executor);
    }

    /**
     * LlamaParse 缺页补全解析器：mock LlamaParse 启用且返回缺页结果（缺第 3 页），
     * 识图可用；fillMissingPages=true。用于验证缺页识图补全链路。
     */
    private DocumentParserService missingPageParser(Executor executor, boolean visionConfigured) {
        QaTracing noopTracing = QaTracing.disabled();
        visionMock = mock(VisionOcrService.class);
        when(visionMock.isConfigured(any())).thenReturn(visionConfigured);
        WorkspaceIdResolver resolver = mock(WorkspaceIdResolver.class);
        when(resolver.resolve(1L)).thenReturn(1L);
        LlamaParseService llamaMock = mock(LlamaParseService.class);
        when(llamaMock.isConfigured()).thenReturn(true);
        when(llamaMock.parseToMarkdown(any(), any(), anyBoolean())).thenReturn(List.of(
                new LlamaParseService.PageMarkdown(1, "# 第一页\n\n第一页正文内容。"),
                new LlamaParseService.PageMarkdown(2, "## 3.2 收不到数据的上一节\n\n第二页正文内容。")));
        when(llamaMock.detectMissingPages(any(), any())).thenReturn(List.of(3));
        return build(visionMock, llamaMock, mock(PptxParserService.class), mock(ExcelParserService.class), resolver,
                noopTracing, docProps(true, 3, true), executor);
    }

    /** 用 PDFBox 内存构造临时 PDF：可含一行文字与一个红色方块图片 */
    private Path createPdf(String text, boolean withImage) throws Exception {
        Path file = tempDir.resolve((withImage ? "img-" : "text-") + UUID.randomUUID() + ".pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(50, 700);
                cs.showText(text);
                cs.endText();
                if (withImage) {
                    BufferedImage img = new BufferedImage(200, 100, BufferedImage.TYPE_INT_RGB);
                    Graphics2D g = img.createGraphics();
                    g.setColor(Color.WHITE);
                    g.fillRect(0, 0, 200, 100);
                    g.setColor(Color.RED);
                    g.fillRect(20, 20, 60, 60);
                    g.dispose();
                    PDImageXObject ximage = LosslessFactory.createFromImage(doc, img);
                    cs.drawImage(ximage, 50, 550, 200, 100);
                }
            }
            doc.save(file.toFile());
        }
        return file;
    }

    /** 用 PDFBox 内存构造临时两页 PDF：第一页长文本（≥2 块），第二页续写正文 */
    private Path createPdf2Pages() throws Exception {
        Path file = tempDir.resolve("two-pages-" + UUID.randomUUID() + ".pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page1 = new PDPage();
            doc.addPage(page1);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page1)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 11);
                cs.newLineAtOffset(50, 720);
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < 30; i++) {
                    sb.append("Page one content line number ").append(i).append(". ");
                }
                cs.showText(sb.toString());
                cs.endText();
            }
            PDPage page2 = new PDPage();
            doc.addPage(page2);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page2)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 11);
                cs.newLineAtOffset(50, 720);
                cs.showText("Page two content continues from the previous page here.");
                cs.endText();
            }
            doc.save(file.toFile());
        }
        return file;
    }

    private Document pdfDoc(Path file) {
        Document doc = new Document();
        doc.setFilePath(file.toString());
        doc.setFileType("pdf");
        doc.setKbId(1L);
        return doc;
    }

    @Test
    void parseTxt_shortParagraphsMergedIntoSingleChunk() throws Exception {
        Path file = tempDir.resolve("test.txt");
        Files.writeString(file, "第一条知识内容。\n\n第二条知识内容，需要被正确分块。");

        Document doc = new Document();
        doc.setFilePath(file.toString());
        doc.setFileType("txt");

        // 分块为累积式：两段短文本合并为一块
        List<ChunkPiece> pieces = parser().parse(doc);
        assertEquals(1, pieces.size());
        assertTrue(pieces.get(0).content().contains("第一条"));
        assertTrue(pieces.get(0).content().contains("第二条"));
        assertEquals(0, pieces.get(0).pageNum(), "txt 页码应为 0");
    }

    @Test
    void parseTxt_longContent_splitIntoMultipleChunks() throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 80; i++) {
            sb.append("第").append(i).append("段，这是用于测试的长篇内容，保证累积超过分块阈值。\n\n");
        }
        Path file = tempDir.resolve("long.txt");
        Files.writeString(file, sb.toString());

        Document doc = new Document();
        doc.setFilePath(file.toString());
        doc.setFileType("txt");

        List<ChunkPiece> pieces = parser().parse(doc);
        assertTrue(pieces.size() > 1, "长内容应被切为多块");
        assertTrue(pieces.stream().allMatch(c -> c.content().length() <= 800));
    }

    @Test
    void parseMd_worksSameAsText() throws Exception {
        Path file = tempDir.resolve("test.md");
        Files.writeString(file, "# 标题\n\n正文段落内容。");

        Document doc = new Document();
        doc.setFilePath(file.toString());
        doc.setFileType("md");

        List<ChunkPiece> pieces = parser().parse(doc);
        assertTrue(!pieces.isEmpty());
    }

    @Test
    void unsupportedType_throws() throws Exception {
        Path file = tempDir.resolve("test.docx");
        Files.writeString(file, "binary");
        Document doc = new Document();
        doc.setFilePath(file.toString());
        doc.setFileType("docx");
        assertThrows(BizException.class, () -> parser().parse(doc));
    }

    @Test
    void excelType_delegatesToExcelParser() throws Exception {
        ExcelParserService excel = mock(ExcelParserService.class);
        when(excel.parse(any(), any())).thenReturn(List.of(new ChunkPiece("| a |", 1, "t")));
        DocumentParserService service = build(mock(VisionOcrService.class), mock(LlamaParseService.class),
                mock(PptxParserService.class), excel, mock(WorkspaceIdResolver.class),
                QaTracing.disabled(), docProps(false, 1, false), null);
        Path file = tempDir.resolve("test.xlsx");
        Files.write(file, new byte[]{0x50, 0x4B});
        Document doc = new Document();
        doc.setFilePath(file.toString());
        doc.setFileName("test.xlsx");
        doc.setFileType("xlsx");

        List<ChunkPiece> pieces = service.parse(doc);

        verify(excel).parse(any(), eq("test.xlsx"));
        assertEquals(1, pieces.size());
    }

    @Test
    void missingFile_throws() {
        Document doc = new Document();
        doc.setFilePath(tempDir.resolve("not-exist.txt").toString());
        doc.setFileType("txt");
        assertThrows(BizException.class, () -> parser().parse(doc));
    }

    @Test
    void pdfTextOnlyPage_skipsVision() throws Exception {
        DocumentParserService service = visionParser();
        // 英文文本（Helvetica 内置字体不支持中文编码），长度需超过 minText=10
        Path file = createPdf("This is a plain text page with enough words to exceed the minimum threshold.", false);

        List<ChunkPiece> pieces = service.parse(pdfDoc(file));

        assertFalse(pieces.isEmpty(), "纯文字 PDF 应正常分块");
        assertTrue(pieces.get(0).content().contains("plain text page"), "文字应进入 chunk");
        verify(visionMock, never()).describeImage(any(), anyInt(), anyLong());
    }

    @Test
    void pdfPageWithImage_callsVision() throws Exception {
        DocumentParserService service = visionParser();
        when(visionMock.describeImage(any(), anyInt(), anyLong())).thenReturn(Optional.of("图中有一个红色方块"));
        Path file = createPdf("Page contains text and an image.", true);

        List<ChunkPiece> pieces = service.parse(pdfDoc(file));

        assertFalse(pieces.isEmpty());
        assertTrue(pieces.stream().anyMatch(c -> c.content().contains("【图片/图表识别】")),
                "图片页应并入识图描述");
        assertTrue(pieces.stream().anyMatch(c -> c.content().contains("图中有一个红色方块")));
        verify(visionMock, atLeastOnce()).describeImage(any(), anyInt(), anyLong());
    }

    @Test
    void pdfBlankPage_fallsBackToVisionByMinText() throws Exception {
        DocumentParserService service = visionParser();
        when(visionMock.describeImage(any(), anyInt(), anyLong())).thenReturn(Optional.of("扫描页识别内容"));
        // 无图片但文字为空（扫描页）→ min-text 兜底触发识图
        Path file = createPdf("", false);

        List<ChunkPiece> pieces = service.parse(pdfDoc(file));

        assertFalse(pieces.isEmpty(), "扫描页应经识图产生内容");
        assertTrue(pieces.stream().anyMatch(c -> c.content().contains("扫描页识别内容")));
        verify(visionMock, atLeastOnce()).describeImage(any(), anyInt(), anyLong());
    }

    /** 构造 3 页 PDF，每页含少量文字 + 一张图片 */
    private Path createPdf3ImagePages() throws Exception {
        Path file = tempDir.resolve("three-img-" + UUID.randomUUID() + ".pdf");
        try (PDDocument doc = new PDDocument()) {
            for (int p = 1; p <= 3; p++) {
                PDPage page = new PDPage();
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    cs.newLineAtOffset(50, 700);
                    cs.showText("Page " + p + " has some text and an image.");
                    cs.endText();
                    BufferedImage img = new BufferedImage(120, 80, BufferedImage.TYPE_INT_RGB);
                    Graphics2D g = img.createGraphics();
                    g.setColor(Color.RED);
                    g.fillRect(0, 0, 120, 80);
                    g.dispose();
                    PDImageXObject ximage = LosslessFactory.createFromImage(doc, img);
                    cs.drawImage(ximage, 50, 550, 120, 80);
                }
            }
            doc.save(file.toFile());
        }
        return file;
    }

    @Test
    void pdfMultipleImagePages_parallelVision() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            DocumentParserService service = parallelVisionParser(pool);
            when(visionMock.describeParallel(any(), anyInt(), anyLong()))
                    .thenReturn(Optional.of(new VisionOcrService.VisionOutcome("图片描述内容", null)));
            Path file = createPdf3ImagePages();

            List<ChunkPiece> pieces = service.parse(pdfDoc(file));

            // 三个图片页的识图结果全部并入对应页的 chunk
            assertTrue(pieces.stream().anyMatch(c -> c.content().contains("【图片/图表识别】") && c.pageNum() == 1));
            assertTrue(pieces.stream().anyMatch(c -> c.content().contains("【图片/图表识别】") && c.pageNum() == 2));
            assertTrue(pieces.stream().anyMatch(c -> c.content().contains("【图片/图表识别】") && c.pageNum() == 3));
            assertTrue(pieces.stream().allMatch(c -> c.content().contains("图片描述内容")));
            verify(visionMock, times(3)).describeParallel(any(), anyInt(), anyLong());
            // 页码顺序保持（并行收集后按页序拼装）
            List<Integer> pages = pieces.stream().map(ChunkPiece::pageNum).toList();
            for (int i = 1; i < pages.size(); i++) {
                assertTrue(pages.get(i) >= pages.get(i - 1), "并行识图后 chunk 页码应按页序排列");
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void pdfTwoPages_crossPageOverlap() throws Exception {
        DocumentParserService service = visionParser();
        when(visionMock.describeImage(any(), anyInt(), anyLong())).thenReturn(Optional.of("图片描述"));
        Path file = createPdf2Pages();

        List<ChunkPiece> pieces = service.parse(pdfDoc(file));

        List<ChunkPiece> page1 = pieces.stream().filter(c -> c.pageNum() == 1).toList();
        List<ChunkPiece> page2 = pieces.stream().filter(c -> c.pageNum() == 2).toList();
        assertFalse(page1.isEmpty(), "第一页应产生分块");
        assertFalse(page2.isEmpty(), "第二页应产生分块");

        String lastOfPage1 = page1.get(page1.size() - 1).content();
        String firstOfPage2 = page2.get(0).content();
        // 第二页首块应携带第一页末块的尾部重叠（跨页衔接）
        String tailSnippet = lastOfPage1.substring(Math.max(0, lastOfPage1.length() - 60)).trim();
        assertFalse(tailSnippet.isEmpty());
        assertTrue(firstOfPage2.contains(tailSnippet.substring(0, Math.min(20, tailSnippet.length()))),
                "第二页首块应携带第一页尾部重叠内容");
    }

    @Test
    void llamaParseMissingPage_filledByVisionMarkdown() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            DocumentParserService service = missingPageParser(pool, true);
            // 识图模型返回 Markdown（第 1 层 prompt 的输出），带标题层级
            when(visionMock.describeParallelMarkdown(any(), anyInt(), anyLong()))
                    .thenReturn(Optional.of(new VisionOcrService.VisionOutcome(
                            "## 3.3 收不到数据\n\n应用收不到数据时按以下步骤排查。\n\n- 步骤一：检查网络配置\n- 步骤二：检查订阅配置", null)));
            Path file = createPdf3ImagePages();

            List<ChunkPiece> pieces = service.parse(pdfDoc(file));

            List<ChunkPiece> page3 = pieces.stream().filter(c -> c.pageNum() == 3).toList();
            assertFalse(page3.isEmpty(), "缺页第 3 页应被识图补全并产生分块");
            String content = page3.get(0).content();
            assertTrue(content.contains("3.3 收不到数据"), "补页 chunk 应含识图返回的章节标题");
            assertTrue(content.contains("步骤一"), "补页 chunk 应含识图返回的正文内容");
            verify(visionMock, times(1)).describeParallelMarkdown(any(), eq(3), anyLong());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void llamaParseMissingPage_visionNotConfigured_keepsMissing() throws Exception {
        DocumentParserService service = missingPageParser(null, false);
        Path file = createPdf3ImagePages();

        List<ChunkPiece> pieces = service.parse(pdfDoc(file));

        // 识图未配置：缺页保持缺失，不产生第 3 页 chunk，也不调用识图
        assertTrue(pieces.stream().noneMatch(c -> c.pageNum() == 3), "识图未配置时缺页不应被补全");
        verify(visionMock, never()).describeParallelMarkdown(any(), anyInt(), anyLong());
        verify(visionMock, never()).describeImageMarkdown(any(), anyInt(), anyLong());
    }

    @Test
    void normalizeVisionMarkdown_stripsFencesPrefixesAndFixesNewlines() {
        // 围栏包裹 + 废话前缀 + 标题前无换行 + 连续空行
        String raw = "```markdown\n以下是识别结果：\n上一段结尾文字## 3.3 收不到数据\n\n\n\n正文内容。\n```";
        String normalized = VisionPageFiller.normalizeVisionMarkdown(raw);
        assertFalse(normalized.contains("```"), "应剥离代码围栏");
        assertFalse(normalized.contains("以下是识别结果"), "应剥离废话前缀");
        assertTrue(normalized.contains("结尾文字\n## 3.3 收不到数据"), "标题前应补换行且保留层级");
        assertFalse(normalized.contains("\n\n\n"), "连续空行应被压缩");
        assertTrue(normalized.contains("正文内容"), "正文内容应保留");
    }
}
