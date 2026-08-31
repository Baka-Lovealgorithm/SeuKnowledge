package com.ai.konwledgerepo.service.document;

import com.ai.konwledgerepo.config.props.SeuDocumentProperties;
import com.ai.konwledgerepo.service.document.DocumentCleanService.ChunkCleanResult;
import com.ai.konwledgerepo.service.document.DocumentCleanService.PageCleanResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1 规则清洗单元测试：页面级（P1 页眉页脚 / P2 封面 / P3 目录 / P4 空白 / P5 免责声明 / P6 中部保护）
 * 与 chunk 级（E 保护 / A 碎片 / B 图题 / C 重复）逐条验证命中、不命中与边界。
 * 全部为纯内存计算（不调 LlamaParse、不向量化、不落库）。
 */
class DocumentCleanServiceTest {

    private static final SeuDocumentProperties PROPS =
            new SeuDocumentProperties(true, true, 10, 100, 1, 800, 120,
                    new SeuDocumentProperties.LlamaParse(false, "", "https://api.cloud.llamaindex.ai",
                            "cost_effective", "latest", "ch_sim", 5, 900, "", true, false, null), SeuDocumentProperties.Clean.defaults());

    private final DocumentCleanService service = new DocumentCleanService(PROPS);

    // ==================== 页面级：P1 页眉页脚剥离 ====================

    private static LlamaParseService.PageMarkdown page(int n, String md) {
        return new LlamaParseService.PageMarkdown(n, md);
    }

    @Test
    void p1_stripsRepeatedHeaderFooterLines_acrossPages() {
        // 3 页：每页首行页眉 + 末行页码（数字变化）+ 长正文（>50 字，避免与封面/空白判定纠缠）
        List<LlamaParseService.PageMarkdown> pages = List.of(
                page(1, "ZRDDS 安装配置手册\n正文第一页内容，这是一段足够长的正文文本，用于验证页眉页脚剥离规则不会误删正文。\n第 1 页"),
                page(2, "ZRDDS 安装配置手册\n正文第二页内容，这是一段足够长的正文文本，用于验证页眉页脚剥离规则不会误删正文。\n第 2 页"),
                page(3, "ZRDDS 安装配置手册\n正文第三页内容，这是一段足够长的正文文本，用于验证页眉页脚剥离规则不会误删正文。\n第 3 页"));

        DocumentCleanService.PageCleanResult result = service.cleanPages(pages);

        assertEquals(3, result.pages().size(), "剥行不删页");
        for (LlamaParseService.PageMarkdown p : result.pages()) {
            assertFalse(p.markdown().contains("ZRDDS 安装配置手册"), "页眉模板行应被剥离");
            assertFalse(p.markdown().contains("第 "), "页码型页脚（数字归一化）应被剥离");
            assertTrue(p.markdown().contains("正文"), "正文应保留");
        }
        assertEquals(1, result.actions().size());
        assertTrue(result.actions().get(0).ruleId().equals("P1"));
    }

    @Test
    void p1_lowFrequencyLine_notStripped() {
        // 2 页：页眉行只出现 1 次（出现率 0.5 < 0.8）→ 不剥；正文足够长避免封面判定
        List<LlamaParseService.PageMarkdown> pages = List.of(
                page(1, "ZRDDS 安装配置手册\n正文一页，这是一段足够长的正文文本用于验证低频行不会被剥离。"),
                page(2, "正文二页，这是一段足够长的正文文本，内容与第一页完全不同用于对照。"));

        DocumentCleanService.PageCleanResult result = service.cleanPages(pages);

        assertTrue(result.pages().get(0).markdown().contains("ZRDDS 安装配置手册"), "低频行不应被剥离");
        assertTrue(result.actions().isEmpty());
    }

    @Test
    void p1_repeatedLineOutsideWindow_notStripped() {
        // 3 页：重复短语位于页面中部（窗口=前 3 行/后 3 行之外）→ 不剥
        List<LlamaParseService.PageMarkdown> pages = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            String md = "第" + i + "页标题\n" + "行1\n行2\n重复短语XYZ\n行4\n行5\n" + "第" + i + "页";
            pages.add(page(i, md));
        }

        DocumentCleanService.PageCleanResult result = service.cleanPages(pages);

        for (LlamaParseService.PageMarkdown p : result.pages()) {
            assertTrue(p.markdown().contains("重复短语XYZ"), "窗口外的重复行不应被剥离（正文保护）");
        }
    }

    @Test
    void p1_longLine_notStripped() {
        // 行长度 > 50（headerFooterMaxLen）：即使每页重复也不剥
        String longLine = "这是一条很长的重复文本用于测试长度保护，超过五十个字符的正文重复行不应被当作页眉页脚，因为页眉页脚通常很短";
        List<LlamaParseService.PageMarkdown> pages = List.of(
                page(1, longLine + "\n正文一"),
                page(2, longLine + "\n正文二"));

        DocumentCleanService.PageCleanResult result = service.cleanPages(pages);

        assertTrue(result.pages().get(0).markdown().contains("很长的重复文本"), "超长行不应被剥离");
    }

    // ==================== 页面级：P2/P3/P4/P5/P6 ====================

    @Test
    void p2_coverPage_headDeleted() {
        List<LlamaParseService.PageMarkdown> pages = List.of(
                page(1, "ZRDDS 安装配置手册\n版本 2.2.5"),
                page(2, "# 1. 安装环境要求\n正文内容第二页。"));

        DocumentCleanService.PageCleanResult result = service.cleanPages(pages);

        assertEquals(1, result.pages().size(), "封面页（≤50 字）应被删除");
        assertEquals(2, result.pages().get(0).pageNumber(), "剩余页 pageNumber 不重排");
        assertTrue(result.actions().stream().anyMatch(a -> a.ruleId().equals("P2")));
    }

    @Test
    void p3_tocPage_headDeleted() {
        List<LlamaParseService.PageMarkdown> pages = List.of(
                page(1, "目录\n1. 安装环境要求..........5\n2. 安装与配置........10\n3. 运行........15"),
                page(2, "# 1. 安装环境要求\n正文内容第二页。"));

        DocumentCleanService.PageCleanResult result = service.cleanPages(pages);

        assertEquals(1, result.pages().size(), "目录页应被删除");
        assertTrue(result.actions().stream().anyMatch(a -> a.ruleId().equals("P3")));
    }

    @Test
    void p4_blankPages_headAndTailDeleted_middleKept() {
        List<LlamaParseService.PageMarkdown> pages = List.of(
                page(1, "  \n  "),
                page(2, "# 1. 章节\n正文第二页。"),
                page(3, "   \n"),
                page(4, "正文第四页。"),
                page(5, "\n\n"));

        DocumentCleanService.PageCleanResult result = service.cleanPages(pages);

        // 第 1 页（头部空白）与第 5 页（尾部空白）删除；中部第 3 页空白保留（P6 中部保护）
        List<Integer> pageNums = result.pages().stream().map(LlamaParseService.PageMarkdown::pageNumber).toList();
        assertEquals(List.of(2, 3, 4), pageNums, "仅首尾空白页删除，中部空白页保留（P6）");
        assertTrue(result.actions().stream().anyMatch(a -> a.ruleId().equals("P4")));
    }

    @Test
    void p5_disclaimerTail_keptAndRecorded() {
        List<LlamaParseService.PageMarkdown> pages = List.of(
                page(1, "# 1. 章节\n正文第一页。"),
                page(2, "本手册版权归臻融软件科技有限公司所有，未经许可不得复制。"));

        DocumentCleanService.PageCleanResult result = service.cleanPages(pages);

        assertEquals(2, result.pages().size(), "免责声明页 SUSPECT：保留不删（页面级降级为统计）");
        assertTrue(result.actions().stream().anyMatch(a -> a.ruleId().equals("P5")));
    }

    @Test
    void singlePageDocument_notDeleted() {
        List<LlamaParseService.PageMarkdown> pages = List.of(page(1, "  "));

        DocumentCleanService.PageCleanResult result = service.cleanPages(pages);

        assertEquals(1, result.pages().size(), "单页文档不删（保护内容）");
    }

    // ==================== chunk 级：E 保护规则 ====================

    @Test
    void e1_tableContent_protected() {
        ChunkCleanResult result = service.cleanChunks(List.of(
                new ChunkPiece("| 操作系统 | 版本 |\n| --- | --- |\n| Windows | XP |", 1, "表 1 环境")));

        assertTrue(result.outcomes().isEmpty(), "表格内容受 E1 保护");
        assertEquals(1, result.kept().size());
    }

    @Test
    void e2_keyValue_shortProtected() {
        ChunkCleanResult result = service.cleanChunks(List.of(new ChunkPiece("端口：3306", 1, null)));

        assertTrue(result.outcomes().isEmpty(), "数值键值（短但高价值）受 E2 保护");
        assertEquals(1, result.kept().size());
    }

    @Test
    void e3_figureWithContent_protected() {
        ChunkCleanResult result = service.cleanChunks(List.of(new ChunkPiece(
                "图 1-1 系统架构图。图中展示了臻融数据分发服务的发布订阅核心组件及其相互关系，包括数据写入者、数据读取者与传输层。",
                1, null)));

        assertTrue(result.outcomes().isEmpty(), "图题 + 长内容（视觉内容本体）受 E3 保护");
        assertEquals(1, result.kept().size());
    }

    @Test
    void e4_titleLine_protected() {
        ChunkCleanResult result = service.cleanChunks(List.of(
                new ChunkPiece("# 2.4.2 配置链接库", 1, "2.4.2 配置链接库"),
                new ChunkPiece("**ZRDDS 安装**", 1, null)));

        assertTrue(result.outcomes().isEmpty(), "标题行受 E4 保护");
        assertEquals(2, result.kept().size());
    }

    @Test
    void e5_overlapWithNeighbor_protected() {
        String tail = "abcdefghijabcdefghijabcdefghijabcdefghij";
        String prev = "前面内容。" + tail;
        String next = tail + "后续内容。";
        // 若 E5 不保护，next 与 prev 的 3-gram Jaccard 很高会被 C2 误判
        ChunkCleanResult result = service.cleanChunks(List.of(
                new ChunkPiece(prev, 1, "t"),
                new ChunkPiece(next, 1, "t")));

        assertTrue(result.outcomes().isEmpty(), "跨页 carry 重叠（E5）应放行");
        assertEquals(2, result.kept().size());
    }

    // ==================== chunk 级：A 碎片规则 ====================

    @Test
    void a1_blank_autoDrop() {
        ChunkCleanResult result = service.cleanChunks(List.of(new ChunkPiece("   \n\t", 1, null)));

        assertEquals(1, result.outcomes().size());
        assertEquals("A1", result.outcomes().get(0).ruleId());
        assertEquals(DocumentCleanService.Disposition.AUTO_DROP, result.outcomes().get(0).disposition());
        assertTrue(result.kept().isEmpty(), "A1 在白名单：AUTO-DROP 不进 kept");
    }

    @Test
    void a2_symbolsOnly_autoDrop() {
        ChunkCleanResult result = service.cleanChunks(List.of(new ChunkPiece("------", 1, null)));

        assertEquals("A2", result.outcomes().get(0).ruleId());
        assertEquals(DocumentCleanService.Disposition.AUTO_DROP, result.outcomes().get(0).disposition());
    }

    @Test
    void a3_url_suspect() {
        ChunkCleanResult result = service.cleanChunks(List.of(new ChunkPiece("https://example.com/zrdds/download", 1, null)));

        assertEquals("A3", result.outcomes().get(0).ruleId());
        assertEquals(DocumentCleanService.Disposition.SUSPECT, result.outcomes().get(0).disposition());
        assertEquals(1, result.kept().size(), "A3 在 suspect 名单：打标照常入库");
    }

    @Test
    void a4_shortFragment_suspect() {
        // 无中文无数字的短文本 → SUSPECT
        ChunkCleanResult r1 = service.cleanChunks(List.of(new ChunkPiece("abc", 1, null)));
        assertEquals("A4", r1.outcomes().get(0).ruleId());
        assertEquals(DocumentCleanService.Disposition.SUSPECT, r1.outcomes().get(0).disposition());
        // 有数字的短文本（如 "10"）→ SUSPECT
        ChunkCleanResult r2 = service.cleanChunks(List.of(new ChunkPiece("10", 1, null)));
        assertEquals("A4", r2.outcomes().get(0).ruleId());
        // 含中文且含数字的短语（如 "端口 3306"）→ 不命中（信息完整）
        ChunkCleanResult r3 = service.cleanChunks(List.of(new ChunkPiece("端口 3306", 1, null)));
        assertTrue(r3.outcomes().isEmpty(), "含中文且含数字的短语不按 A4 清洗");
        // 含中文但无数字的极短碎片（如 "端口"）→ 命中 A4（无信息量）
        ChunkCleanResult r4 = service.cleanChunks(List.of(new ChunkPiece("端口", 1, null)));
        assertEquals("A4", r4.outcomes().get(0).ruleId());
    }

    @Test
    void a5_garbled_autoDrop() {
        ChunkCleanResult result = service.cleanChunks(List.of(new ChunkPiece(
                "这是一段包含乱码\uFFFD\uFFFD\uFFFD\uFFFD\uFFFD\uFFFD字符的测试文本，用于验证乱码检测规则。", 1, null)));

        assertEquals("A5", result.outcomes().get(0).ruleId());
        assertEquals(DocumentCleanService.Disposition.AUTO_DROP, result.outcomes().get(0).disposition());
    }

    // ==================== chunk 级：B 图题规则 ====================

    @Test
    void b1_isolatedFigureTitle_suspect() {
        ChunkCleanResult result = service.cleanChunks(List.of(new ChunkPiece("图 1-1 系统架构", 1, null)));

        assertEquals("B1", result.outcomes().get(0).ruleId());
        assertEquals(DocumentCleanService.Disposition.SUSPECT, result.outcomes().get(0).disposition());
    }

    @Test
    void b2_isolatedTableTitle_suspect() {
        ChunkCleanResult result = service.cleanChunks(List.of(new ChunkPiece("表 2 参数说明", 1, null)));

        assertEquals("B2", result.outcomes().get(0).ruleId());
        assertEquals(DocumentCleanService.Disposition.SUSPECT, result.outcomes().get(0).disposition());
    }

    @Test
    void b3_imageOnly_suspect() {
        ChunkCleanResult r1 = service.cleanChunks(List.of(new ChunkPiece("<img src=\"shot.png\" alt=\"安装向导窗口\">", 1, null)));
        assertEquals("B3", r1.outcomes().get(0).ruleId());
        assertEquals(DocumentCleanService.Disposition.SUSPECT, r1.outcomes().get(0).disposition());

        ChunkCleanResult r2 = service.cleanChunks(List.of(new ChunkPiece("![截图](shot.png)", 1, null)));
        assertEquals("B3", r2.outcomes().get(0).ruleId());
    }

    // ==================== chunk 级：C 重复规则 ====================

    @Test
    void c1_templateText_autoDropKeepsFirst() {
        ChunkPiece first = new ChunkPiece("臻融软件科技有限公司 版权所有", 1, null);
        ChunkPiece second = new ChunkPiece("臻融软件科技有限公司 版权所有", 2, null);
        ChunkPiece third = new ChunkPiece("正文内容第三段，这是一段足够长的正常正文用于验证模板保留逻辑。", 3, null);

        ChunkCleanResult result = service.cleanChunks(List.of(first, second, third));

        // 模板出现率 2/3 ≥ 0.6：第二个命中 C1（AUTO-DROP），首个保留
        assertEquals(1, result.outcomes().size());
        assertEquals("C1", result.outcomes().get(0).ruleId());
        assertEquals(DocumentCleanService.Disposition.AUTO_DROP, result.outcomes().get(0).disposition());
        assertEquals(List.of(first, third), result.kept(), "保留首个出现");
    }

    @Test
    void c2_nearDuplicate_suspectKeepsFirst() {
        String base = "臻融数据分发服务 DDS 支持多种 IDE，此处以 eclipse 为例，创建工程并配置链接库。";
        ChunkPiece first = new ChunkPiece(base, 1, "2.4 配置工程");
        ChunkPiece second = new ChunkPiece(base + "补", 2, "2.4 配置工程");

        ChunkCleanResult result = service.cleanChunks(List.of(first, second));

        assertEquals(1, result.outcomes().size());
        assertEquals("C2", result.outcomes().get(0).ruleId());
        assertEquals(DocumentCleanService.Disposition.SUSPECT, result.outcomes().get(0).disposition());
        assertEquals(2, result.kept().size(), "C2 在 suspect 名单：打标照常入库（保留首个为 SUSPECT 的区分依赖 kept 顺序）");
    }

    @Test
    void c3_longLineDuplicate_suspect() {
        String longLine = "C:\\demo\\ZRDDSTestEclipse\\ZRDDSTest\\command>javac -cp \"C:\\Program Files (x86)\\ZRDDS\\ZRDDS-2.2.5\\lib\\ZRDDS_JAVA.jar\" -d out *.java";
        ChunkPiece first = new ChunkPiece("编译示例开头\n" + longLine + "\n编译示例结尾", 6, null);
        ChunkPiece second = new ChunkPiece("另一个说明\n" + longLine + "\n更多说明文字", 7, null);

        ChunkCleanResult result = service.cleanChunks(List.of(first, second));

        assertEquals(1, result.outcomes().size());
        assertEquals("C3", result.outcomes().get(0).ruleId());
        assertEquals(DocumentCleanService.Disposition.SUSPECT, result.outcomes().get(0).disposition());
    }

    @Test
    void differentChunks_allKept() {
        ChunkCleanResult result = service.cleanChunks(List.of(
                new ChunkPiece("第一段完全不同的正常内容。", 1, "t1"),
                new ChunkPiece("第二段完全不同的正常内容。", 2, "t2")));

        assertTrue(result.outcomes().isEmpty());
        assertEquals(2, result.kept().size());
    }

    // ==================== 处置名单 fail-safe ====================

    @Test
    void ruleNotInAnyList_keepOnly() {
        // 自定义配置：autoDropRules 与 suspectRules 均为空 → 命中规则只统计不动作
        SeuDocumentProperties.Clean emptyList = new SeuDocumentProperties.Clean(true, 0.8, 3, 50, 10, 0.9, 0.6, 80, 30, 40,
                List.of(), List.of());
        DocumentCleanService svc = new DocumentCleanService(
                new SeuDocumentProperties(true, true, 10, 100, 1, 800, 120,
                        new SeuDocumentProperties.LlamaParse(false, "", "https://api.cloud.llamaindex.ai",
                                "cost_effective", "latest", "ch_sim", 5, 900, "", true, false, null), emptyList));

        ChunkCleanResult result = svc.cleanChunks(List.of(new ChunkPiece("   ", 1, null)));

        assertEquals(1, result.outcomes().size(), "规则仍判定命中（统计）");
        assertEquals(DocumentCleanService.Disposition.KEEP, result.outcomes().get(0).disposition());
        assertEquals(1, result.kept().size(), "未进任何名单：仅统计不动作（fail-safe）");
    }

    @Test
    void disabled_returnsPassthrough() {
        SeuDocumentProperties.Clean disabled = new SeuDocumentProperties.Clean(false, 0.8, 3, 50, 10, 0.9, 0.6, 80, 30, 40,
                List.of("A1", "A2", "A5", "C1"), List.of());
        DocumentCleanService svc = new DocumentCleanService(
                new SeuDocumentProperties(true, true, 10, 100, 1, 800, 120,
                        new SeuDocumentProperties.LlamaParse(false, "", "https://api.cloud.llamaindex.ai",
                                "cost_effective", "latest", "ch_sim", 5, 900, "", true, false, null), disabled));

        ChunkCleanResult result = svc.cleanChunks(List.of(new ChunkPiece("   ", 1, null)));
        assertTrue(result.outcomes().isEmpty(), "enabled=false 时清洗完全跳过");
        assertEquals(1, result.kept().size());

        DocumentCleanService.PageCleanResult pageResult = svc.cleanPages(
                List.of(new LlamaParseService.PageMarkdown(1, "页眉行\n正文")));
        assertEquals(1, pageResult.pages().size());
        assertTrue(pageResult.actions().isEmpty());
    }

    @Test
    void reason_containsRuleIdAndSnippet() {
        ChunkCleanResult result = service.cleanChunks(List.of(new ChunkPiece("图 1-1 系统架构", 1, null)));

        assertNotNull(result.outcomes().get(0).reason());
        assertTrue(result.outcomes().get(0).reason().startsWith("B1 孤立图题"));
        assertTrue(result.outcomes().get(0).reason().contains("图 1-1"));
    }
}
