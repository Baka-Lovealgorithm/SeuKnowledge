package com.ai.konwledgerepo.service.document;

import com.ai.konwledgerepo.common.BizException;
import com.ai.konwledgerepo.config.props.SeuDocumentProperties;
import com.ai.konwledgerepo.service.storage.DocumentBlobService;
import com.ai.konwledgerepo.tracing.QaTracing;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PPTX 解析服务单元测试（LlamaParse-only，mock 云端与识图）：
 * - LlamaParse 未配置 → BizException；
 * - 纯文字页 → 每页一个 chunk、pageNum=幻灯片号、title 取首标题行；
 * - 图片内容页（md 空/过短 + 有截图）→ VLM 补全（并行/串行）；
 * - 无截图/无 VISION/开关关闭 → 保持原样不调 VLM；
 * - 整篇回退（pageNum=0）→ 单个 chunk。
 */
class PptxParserServiceTest {

    @TempDir
    Path tempDir;

    private LlamaParseService llamaMock;
    private VisionOcrService visionMock;
    private DocumentBlobService blobMock;

    /** 串行解析器（parallel=1）：fillMissingPages=true，visionParsing=true，minText=50 */
    private PptxParserService parser() {
        QaTracing noopTracing = QaTracing.disabled();
        llamaMock = mock(LlamaParseService.class);
        visionMock = mock(VisionOcrService.class);
        blobMock = mock(DocumentBlobService.class);
        return new PptxParserService(llamaMock, visionMock, blobMock, noopTracing,
                new SeuDocumentProperties(true, true, 50, 100, 1, 800, 120,
                        new SeuDocumentProperties.LlamaParse(false, "", "https://api.cloud.llamaindex.ai",
                                "cost_effective", "latest", "ch_sim", 5, 900, "", true, true, null), SeuDocumentProperties.Clean.defaults()),
                null);
    }

    /** 并行解析器（parallel=3 + 真实固定线程池）：verify describeParallelMarkdown */
    private PptxParserService parallelParser(ExecutorService pool) {
        QaTracing noopTracing = QaTracing.disabled();
        llamaMock = mock(LlamaParseService.class);
        visionMock = mock(VisionOcrService.class);
        blobMock = mock(DocumentBlobService.class);
        return new PptxParserService(llamaMock, visionMock, blobMock, noopTracing,
                new SeuDocumentProperties(true, true, 50, 100, 3, 800, 120,
                        new SeuDocumentProperties.LlamaParse(false, "", "https://api.cloud.llamaindex.ai",
                                "cost_effective", "latest", "ch_sim", 5, 900, "", true, true, null), SeuDocumentProperties.Clean.defaults()),
                pool);
    }

    private Path pptxFile() throws Exception {
        return Files.createTempFile(tempDir, "test", ".pptx");
    }

    private static LlamaParseService.PageMarkdown page(int n, String md) {
        return new LlamaParseService.PageMarkdown(n, md);
    }

    private static LlamaParseService.PageMarkdown pageWithShot(int n, String md, String screenshotText) {
        return new LlamaParseService.PageMarkdown(n, md,
                screenshotText == null ? null : screenshotText.getBytes(StandardCharsets.UTF_8));
    }

    // ===== LlamaParse 未配置 =====

    @Test
    void llamaParseNotConfigured_throws() throws Exception {
        PptxParserService service = parser();
        when(llamaMock.isConfigured()).thenReturn(false);
        Path file = pptxFile();

        BizException ex = assertThrows(BizException.class, () -> service.parse(file, null, "test.pptx", 1L));
        assertTrue(ex.getMessage().contains("启用 LlamaParse"));
    }

    // ===== 纯文字页：每页一个 chunk =====

    @Test
    void textPages_oneChunkPerPage() throws Exception {
        PptxParserService service = parser();
        when(llamaMock.isConfigured()).thenReturn(true);
        when(llamaMock.parseToMarkdown(any(), any())).thenReturn(List.of(
                page(1, "# 概述——DDS协议\n\n数据分发服务是 OMG 提出的标准。"),
                page(2, "# 概述——主题\n\n主题连接发布者与订阅者。"),
                page(3, "# 性能——延时/吞吐量\n\n平均延时 54.15us。")));
        Path file = pptxFile();

        List<ChunkPiece> pieces = service.parse(file, null, "test.pptx", 1L);

        assertEquals(3, pieces.size(), "每页应产生一个 chunk");
        assertEquals(1, pieces.get(0).pageNum());
        assertEquals(2, pieces.get(1).pageNum());
        assertEquals(3, pieces.get(2).pageNum());
        assertEquals("概述——DDS协议", pieces.get(0).title(), "title 应取页 md 首标题行");
        assertEquals("性能——延时/吞吐量", pieces.get(2).title());
        assertTrue(pieces.get(2).content().contains("54.15us"), "纯文字页数字应保留");
        verify(visionMock, never()).describeImageMarkdown(any(), anyInt(), anyLong());
    }

    // ===== 图片内容页：md 为空 + 有截图 → 串行识图补全 =====

    @Test
    void imagePage_emptyMd_filledByVisionSerial() throws Exception {
        PptxParserService service = parser();
        when(llamaMock.isConfigured()).thenReturn(true);
        when(llamaMock.parseToMarkdown(any(), any())).thenReturn(List.of(
                page(1, "# 概述——DDS协议\n\n正常文字页内容。"),
                pageWithShot(2, "# 工具与服务——监控工具", "screenshot-bytes")));
        when(visionMock.describeImageMarkdown(any(), eq(2), anyLong()))
                .thenReturn(Optional.of("## 工具与服务——监控工具\n\n报文统计信息，统计各个实体的数据收发数量。"));
        Path file = pptxFile();

        List<ChunkPiece> pieces = service.parse(file, null, "test.pptx", 1L);

        assertEquals(2, pieces.size());
        ChunkPiece page2 = pieces.stream().filter(c -> c.pageNum() == 2).findFirst().orElseThrow();
        assertTrue(page2.content().contains("报文统计信息"), "图片内容页应经识图补全正文");
        assertTrue(page2.content().contains("数据收发数量"));
        verify(visionMock, times(1)).describeImageMarkdown(any(), eq(2), anyLong());
    }

    /** md 过短（< minText=50）+ 有截图 → 也应触发识图补全 */
    @Test
    void imagePage_shortMd_filledByVision() throws Exception {
        PptxParserService service = parser();
        when(llamaMock.isConfigured()).thenReturn(true);
        when(llamaMock.parseToMarkdown(any(), any())).thenReturn(List.of(
                pageWithShot(1, "标题", "screenshot-bytes"))); // md 仅 2 字符 < 50
        when(visionMock.describeImageMarkdown(any(), eq(1), anyLong()))
                .thenReturn(Optional.of("# 标题\n\n完整正文内容，超过五十个字符以便验证图片内容页补全机制正常工作。"));
        Path file = pptxFile();

        List<ChunkPiece> pieces = service.parse(file, null, "test.pptx", 1L);

        assertEquals(1, pieces.size());
        assertTrue(pieces.get(0).content().contains("完整正文内容"));
        verify(visionMock, times(1)).describeImageMarkdown(any(), eq(1), anyLong());
    }

    // ===== 并行路径 =====

    @Test
    void imagePage_parallelVision() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            PptxParserService service = parallelParser(pool);
            when(llamaMock.isConfigured()).thenReturn(true);
            when(llamaMock.parseToMarkdown(any(), any())).thenReturn(List.of(
                    pageWithShot(1, "# 页一\n\n正文文字足够长超过五十个字符的内容。", "shot1"),
                    pageWithShot(2, "# 工具页", "shot2"),
                    pageWithShot(3, "", "shot3")));
            when(visionMock.describeParallelMarkdown(any(), eq(2), anyLong()))
                    .thenReturn(Optional.of(new VisionOcrService.VisionOutcome("# 工具页\n\n工具正文内容。", null)));
            when(visionMock.describeParallelMarkdown(any(), eq(3), anyLong()))
                    .thenReturn(Optional.of(new VisionOcrService.VisionOutcome("# 工具页三\n\n第三页正文。", null)));
            Path file = pptxFile();

            List<ChunkPiece> pieces = service.parse(file, null, "test.pptx", 1L);

            assertEquals(3, pieces.size());
            // 第 1 页 md 足够长（>50）不触发识图；第 2/3 页触发并行识图
            verify(visionMock, times(1)).describeParallelMarkdown(any(), eq(2), anyLong());
            verify(visionMock, times(1)).describeParallelMarkdown(any(), eq(3), anyLong());
            ChunkPiece p2 = pieces.stream().filter(c -> c.pageNum() == 2).findFirst().orElseThrow();
            ChunkPiece p3 = pieces.stream().filter(c -> c.pageNum() == 3).findFirst().orElseThrow();
            assertTrue(p2.content().contains("工具正文内容"));
            assertTrue(p3.content().contains("第三页正文"));
        } finally {
            pool.shutdownNow();
        }
    }

    // ===== 无截图 / 无 VISION / 开关关闭 → 保持原样 =====

    @Test
    void imagePage_noScreenshot_keepsOriginal() throws Exception {
        PptxParserService service = parser();
        when(llamaMock.isConfigured()).thenReturn(true);
        // 页 2 有 md 但无截图（screenshot=null）→ 不触发识图
        when(llamaMock.parseToMarkdown(any(), any())).thenReturn(List.of(
                page(1, "# 一\n\n正常文字内容。"),
                page(2, "# 工具页\n\n仅标题的短内容")));
        Path file = pptxFile();

        List<ChunkPiece> pieces = service.parse(file, null, "test.pptx", 1L);

        assertEquals(2, pieces.size());
        verify(visionMock, never()).describeImageMarkdown(any(), anyInt(), anyLong());
        verify(visionMock, never()).describeParallelMarkdown(any(), anyInt(), anyLong());
    }

    @Test
    void imagePage_visionNotConfigured_keepsOriginal() throws Exception {
        // visionParsing=false → needsVision 恒 false
        QaTracing noopTracing = QaTracing.disabled();
        llamaMock = mock(LlamaParseService.class);
        visionMock = mock(VisionOcrService.class);
        PptxParserService service = new PptxParserService(llamaMock, visionMock, mock(DocumentBlobService.class), noopTracing,
                new SeuDocumentProperties(false, true, 50, 100, 1, 800, 120,
                        new SeuDocumentProperties.LlamaParse(false, "", "https://api.cloud.llamaindex.ai",
                                "cost_effective", "latest", "ch_sim", 5, 900, "", true, true, null), SeuDocumentProperties.Clean.defaults()),
                null);
        when(llamaMock.isConfigured()).thenReturn(true);
        when(llamaMock.parseToMarkdown(any(), any())).thenReturn(List.of(
                pageWithShot(1, "", "screenshot-bytes")));
        Path file = pptxFile();

        // md 为空且无识图 → 该页跳过（无 chunk）
        List<ChunkPiece> pieces = service.parse(file, null, "test.pptx", 1L);

        assertTrue(pieces.isEmpty(), "无识图时空白页应被跳过");
        verify(visionMock, never()).describeImageMarkdown(any(), anyInt(), anyLong());
    }

    @Test
    void imagePage_fillDisabled_keepsOriginal() throws Exception {
        QaTracing noopTracing = QaTracing.disabled();
        llamaMock = mock(LlamaParseService.class);
        visionMock = mock(VisionOcrService.class);
        // fillMissingPages=false → 不做识图补全
        PptxParserService service = new PptxParserService(llamaMock, visionMock, mock(DocumentBlobService.class), noopTracing,
                new SeuDocumentProperties(true, true, 50, 100, 1, 800, 120,
                        new SeuDocumentProperties.LlamaParse(false, "", "https://api.cloud.llamaindex.ai",
                                "cost_effective", "latest", "ch_sim", 5, 900, "", true, false, null), SeuDocumentProperties.Clean.defaults()),
                null);
        when(llamaMock.isConfigured()).thenReturn(true);
        when(llamaMock.parseToMarkdown(any(), any())).thenReturn(List.of(
                pageWithShot(1, "# 工具页", "screenshot-bytes")));
        Path file = pptxFile();

        List<ChunkPiece> pieces = service.parse(file, null, "test.pptx", 1L);

        assertEquals(1, pieces.size());
        assertEquals("工具页", pieces.get(0).title());
        verify(visionMock, never()).describeImageMarkdown(any(), anyInt(), anyLong());
    }

    // ===== 整篇回退 =====

    @Test
    void wholeMarkdownFallback_singleChunkPageZero() throws Exception {
        PptxParserService service = parser();
        when(llamaMock.isConfigured()).thenReturn(true);
        when(llamaMock.parseToMarkdown(any(), any())).thenReturn(List.of(
                page(0, "# 整篇内容\n\nLlamaParse 未返回逐页结果时的整篇 markdown。")));
        Path file = pptxFile();

        List<ChunkPiece> pieces = service.parse(file, null, "test.pptx", 1L);

        assertEquals(1, pieces.size());
        assertEquals(0, pieces.get(0).pageNum());
        assertEquals("整篇内容", pieces.get(0).title());
    }

    // ===== 标题提取 =====

    /** footer=null（无页脚）时的普通场景 */
    @Test
    void extractTitle_headingAndPlainText() {
        assertEquals("章节标题", PptxParserService.extractTitle("## 章节标题\n\n正文", null));
        assertEquals("纯文本标题", PptxParserService.extractTitle("纯文本标题\n正文内容", null));
        String longTitle = "长标题" + "长".repeat(200);
        assertEquals(100, PptxParserService.extractTitle(longTitle, null).length(), "超长标题应截断到 100 字符");
        assertEquals(null, PptxParserService.extractTitle("", null));
        assertEquals(null, PptxParserService.extractTitle(null, null));
        assertEquals(null, PptxParserService.extractTitle("\n  \n", null));
    }

    /** 首个标题即真实标题（页脚为纯文本在前，或无页脚）→ 首个标题为根；后续子标题并入祖先链 */
    @Test
    void extractTitle_firstHeadingIsTitle() {
        assertEquals("概述—— DDS 协议",
                PptxParserService.extractTitle("# 概述—— DDS 协议\n\n* 数据分发服务…", null));
        assertEquals("概述—— 使用场景",
                PptxParserService.extractTitle("# 概述—— 使用场景\n\n* 作为开放式架构", null));
        // 首个标题 + 正文子标题（footer=null 时不跳过首个标题）→ 子标题并入路径
        assertEquals("概述—— 使用场景 > 子标题",
                PptxParserService.extractTitle("# 概述—— 使用场景\n\n## 子标题\n\n* 正文", null));
    }

    /** 页脚以标题形式出现（footer="臻融科技"）→ 跳过页脚，从第二个标题起构建祖先链 */
    @Test
    void extractTitle_footerHeading_skipped() {
        String footer = "臻融科技";
        assertEquals("工具与服务——诊断工具",
                PptxParserService.extractTitle("# 臻融科技\n\n## 工具与服务——诊断工具\n\n正文内容", footer));
        assertEquals("工具与服务——诊断工具",
                PptxParserService.extractTitle("# 臻融科技\n\n# 工具与服务——诊断工具\n\n正文内容", footer));
        // 3 个标题（页脚 + 真实标题 + 更高级别标题）→ 页脚跳过，最后一个标题按层级重置路径
        assertEquals("ZRDDS问题诊断工具",
                PptxParserService.extractTitle("# 臻融科技\n\n## 工具与服务——诊断工具\n\n# ZRDDS问题诊断工具\n\n* 运行环境检测", footer));
    }

    /** 页脚为普通文本（非标题）+ 单个真实标题 → 取该标题 */
    @Test
    void extractTitle_plainFooter_thenHeading() {
        String footer = "臻融科技";
        assertEquals("联系方式",
                PptxParserService.extractTitle("臻融科技\n\n# 联系方式\n\n南京臻融科技有限公司\n地址：…", footer));
        assertEquals("大纲", PptxParserService.extractTitle("臻融科技\n\n# 大纲\n\n* DDS\n* ZRDDS", footer));
        assertEquals("ZRDDS 介绍", PptxParserService.extractTitle("≡\n\n# ZRDDS 介绍\n南京臻融科技有限公司", footer));
    }

    /** 页脚为标题且真实标题是紧随的短纯文本行（如 `# 臻融科技` + `工具与服务——启动器`）→ 取纯文本行 */
    @Test
    void extractTitle_footerHeading_thenPlainTextTitle() {
        String footer = "臻融科技";
        assertEquals("工具与服务——启动器",
                PptxParserService.extractTitle("# 臻融科技\n\n工具与服务——启动器\n\nZRDDS启动器界面\n\n* 说明文档", footer));
        // 页脚标题后跟列表（无纯文本标题）→ 回退页脚本身
        assertEquals("臻融科技",
                PptxParserService.extractTitle("# 臻融科技\n\n* 说明文档\n* 接口手册", footer));
    }

    // ===== 页脚跨页检测 =====

    @Test
    void detectFooter_repeatedShortHeading() {
        List<LlamaParseService.PageMarkdown> pages = new java.util.ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            // 7 页以"臻融科技"为首个标题，其余 3 页不同
            String md = i <= 7 ? "# 臻融科技\n\n## 标题" + i + "\n\n正文" : "# 标题" + i + "\n\n正文";
            pages.add(new LlamaParseService.PageMarkdown(i, md));
        }
        assertEquals("臻融科技", PptxParserService.detectFooter(pages));
    }

    @Test
    void detectFooter_noRepeatedHeading_returnsNull() {
        List<LlamaParseService.PageMarkdown> pages = new java.util.ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            pages.add(new LlamaParseService.PageMarkdown(i, "# 标题" + i + "\n\n正文"));
        }
        assertEquals(null, PptxParserService.detectFooter(pages));
        assertEquals(null, PptxParserService.detectFooter(List.of(new LlamaParseService.PageMarkdown(1, "# 标题"))));
        assertEquals(null, PptxParserService.detectFooter(null));
    }

    // ===== 结果一致性：页码排序 =====

    @Test
    void pagesSortedByNumber_evenWhenOutOfOrder() throws Exception {
        PptxParserService service = parser();
        when(llamaMock.isConfigured()).thenReturn(true);
        when(llamaMock.parseToMarkdown(any(), any())).thenReturn(List.of(
                page(3, "# 第三页\n\n内容三。"),
                page(1, "# 第一页\n\n内容一。"),
                page(2, "# 第二页\n\n内容二。")));
        Path file = pptxFile();

        List<ChunkPiece> pieces = service.parse(file, null, "test.pptx", 1L);

        assertEquals(List.of(1, 2, 3), pieces.stream().map(ChunkPiece::pageNum).toList(), "chunk 应按页码升序");
        assertNotNull(pieces.get(0).content());
    }
}
