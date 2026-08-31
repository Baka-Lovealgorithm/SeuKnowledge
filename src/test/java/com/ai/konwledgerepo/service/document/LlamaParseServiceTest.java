package com.ai.konwledgerepo.service.document;

import com.ai.konwledgerepo.config.props.SeuDocumentProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LlamaParseService 缺页检测（detectMissingPages）单元测试：
 * 用 PDFBox 内存构造临时 PDF（PDF 实际页数），与返回的逐页 md 页码集合做差集。
 */
class LlamaParseServiceTest {

    @TempDir
    Path tempDir;

    /** 默认参数构造（缺页检测不依赖解析配置；enabled=false 不影响检测逻辑） */
    private LlamaParseService service() {
        return new LlamaParseService(
                new SeuDocumentProperties(true, true, 50, 100, 3, 800, 120,
                        new SeuDocumentProperties.LlamaParse(false, "", "https://api.cloud.llamaindex.ai",
                                "cost_effective", "latest", "ch_sim", 5, 900, "", false, true, null), SeuDocumentProperties.Clean.defaults()),
                new ObjectMapper());
    }

    /** 构造 N 页的临时 PDF，每页一行英文文本（Helvetica 内置字体不支持中文编码） */
    private Path createPdf(int pages) throws Exception {
        Path file = tempDir.resolve("pages-" + pages + "-" + UUID.randomUUID() + ".pdf");
        try (PDDocument doc = new PDDocument()) {
            for (int p = 1; p <= pages; p++) {
                PDPage page = new PDPage();
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    cs.newLineAtOffset(50, 700);
                    cs.showText("Page " + p + " content.");
                    cs.endText();
                }
            }
            doc.save(file.toFile());
        }
        return file;
    }

    private static LlamaParseService.PageMarkdown page(int n, String md) {
        return new LlamaParseService.PageMarkdown(n, md);
    }

    @Test
    void missingMiddlePage_detected() throws Exception {
        Path pdf = createPdf(3);
        // LlamaParse 返回第 1、3 页，缺第 2 页
        List<Integer> missing = service().detectMissingPages(pdf, List.of(page(1, "# One"), page(3, "# Three")));
        assertEquals(List.of(2), missing);
    }

    @Test
    void missingTailPage_detected() throws Exception {
        Path pdf = createPdf(3);
        List<Integer> missing = service().detectMissingPages(pdf, List.of(page(1, "a"), page(2, "b")));
        assertEquals(List.of(3), missing);
    }

    @Test
    void multipleMissingPages_ascendingOrder() throws Exception {
        Path pdf = createPdf(6);
        List<Integer> missing = service().detectMissingPages(pdf, List.of(page(2, "b"), page(5, "e")));
        assertEquals(List.of(1, 3, 4, 6), missing, "缺失页应按升序返回");
    }

    @Test
    void allPagesReturned_noMissing() throws Exception {
        Path pdf = createPdf(3);
        List<Integer> missing = service().detectMissingPages(pdf,
                List.of(page(1, "a"), page(2, "b"), page(3, "c")));
        assertTrue(missing.isEmpty());
    }

    @Test
    void blankMarkdownPage_countsAsMissing() throws Exception {
        Path pdf = createPdf(3);
        // 第 2 页 md 为空（fetchMarkdown 会过滤），应视为缺失页
        List<Integer> missing = service().detectMissingPages(pdf,
                List.of(page(1, "a"), page(2, ""), page(3, "c")));
        assertEquals(List.of(2), missing, "md 为空的页应计为缺失页");
    }

    @Test
    void wholeMarkdownFallback_pageZero_skipsDetection() throws Exception {
        Path pdf = createPdf(3);
        // 整篇 markdown 回退：页码为 0，无法定位页码，放弃页级检测
        List<Integer> missing = service().detectMissingPages(pdf, List.of(page(0, "# 整篇内容")));
        assertTrue(missing.isEmpty(), "整篇回退时应放弃缺页检测");
    }

    @Test
    void nonexistentFile_returnsEmpty() throws Exception {
        List<Integer> missing = service().detectMissingPages(
                tempDir.resolve("not-exist-" + UUID.randomUUID() + ".pdf"), List.of(page(1, "a")));
        assertTrue(missing.isEmpty(), "PDF 读取异常时应返回空而非抛错");
    }

    @Test
    void emptyPagesList_returnsAllPages() throws Exception {
        Path pdf = createPdf(2);
        List<Integer> missing = service().detectMissingPages(pdf, List.of());
        assertEquals(List.of(1, 2), missing, "无返回页时所有物理页均为缺失");
    }

    // ===== needScreenshot：截图按需下载判定 =====

    @Test
    void needScreenshot_nullOrBlank_md() {
        assertTrue(LlamaParseService.needScreenshot(null, 50), "null md 需要截图（识图补全）");
        assertTrue(LlamaParseService.needScreenshot("", 50), "空 md 需要截图");
        assertTrue(LlamaParseService.needScreenshot("   ", 50), "空白 md 需要截图");
    }

    @Test
    void needScreenshot_shortMd_belowThreshold() {
        assertTrue(LlamaParseService.needScreenshot("只有标题", 50), "md 短于 vision-min-text 需要截图");
        assertTrue(LlamaParseService.needScreenshot("a".repeat(49), 50), "49 字符 < 50 需要截图");
    }

    @Test
    void needScreenshot_longMd_skipDownload() {
        assertFalse(LlamaParseService.needScreenshot("a".repeat(50), 50), "50 字符达到阈值不需要截图");
        assertFalse(LlamaParseService.needScreenshot("正文内容充足".repeat(20), 50), "文本充足页不下载截图");
    }
}
