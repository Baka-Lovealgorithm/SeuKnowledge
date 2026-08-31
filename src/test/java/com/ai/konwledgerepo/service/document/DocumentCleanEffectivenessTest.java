package com.ai.konwledgerepo.service.document;

import com.ai.konwledgerepo.config.props.SeuDocumentProperties;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * P1 清洗效果评判（真实样本）：读取 LlamaParse 已导出的
 * {@code data/llamaparse/ZRDDS______-Java.pdf.md}（gitignored，不入库），
 * 按 {@code <!-- PAGE N -->} 标记拆页模拟 LlamaParse 输出，依次执行
 * 页面级清洗 → 递归分块（生产默认 chunk-size=800 / overlap=120）→ chunk 级清洗，
 * 打印各规则命中统计与样例供人工评判。
 * <p>
 * 约束：不调用 LlamaParse 重新解析、不向量化、不落库（纯内存计算）。
 * 运行：{@code .\mvnw.cmd test -Dtest=DocumentCleanEffectivenessTest -DfailIfNoTests=false}
 */
class DocumentCleanEffectivenessTest {

    /** 真实样本路径（相对项目根，mvn test 默认工作目录） */
    private static final Path SAMPLE = Path.of("data/llamaparse/ZRDDS______-Java.pdf.md");
    private static final Pattern PAGE_MARK = Pattern.compile("<!-- PAGE (\\d+) -->");

    private static final SeuDocumentProperties PROPS =
            new SeuDocumentProperties(true, true, 10, 100, 1, 800, 120,
                    new SeuDocumentProperties.LlamaParse(false, "", "https://api.cloud.llamaindex.ai",
                            "cost_effective", "latest", "ch_sim", 5, 900, "", true, false, null), SeuDocumentProperties.Clean.defaults());

    @Test
    void evaluateCleanEffectOnRealSample() throws Exception {
        assumeTrue(Files.exists(SAMPLE),
                "样本 data/llamaparse/ZRDDS______-Java.pdf.md 不存在，跳过效果评判");
        String md = Files.readString(SAMPLE, StandardCharsets.UTF_8);

        // ---- 1) 按 <!-- PAGE N --> 拆页，模拟 LlamaParse 逐页输出 ----
        List<LlamaParseService.PageMarkdown> pages = splitPages(md);
        assertTrue(pages.size() >= 3, "样本应至少 3 页，实际 " + pages.size());

        // ---- 2) 页面级清洗 ----
        DocumentCleanService service = new DocumentCleanService(PROPS);
        DocumentCleanService.PageCleanResult pageClean = service.cleanPages(pages);

        // ---- 3) 递归分块（跨页 carry + 标题栈继承，与生产一致） ----
        List<ChunkPiece> pieces = new ArrayList<>();
        String[] carry = new String[1];
        List<Headings.StackEntry>[] inheritStack = new List[1];
        for (LlamaParseService.PageMarkdown page : pageClean.pages()) {
            if (page.markdown() == null || page.markdown().isBlank()) {
                continue;
            }
            RecursiveChunkSplitter.SplitResult sr = RecursiveChunkSplitter.splitWithCarry(
                    page.markdown(), page.pageNumber(), 800, 120, carry[0], inheritStack[0]);
            pieces.addAll(sr.pieces());
            carry[0] = sr.carryOut() == null || sr.carryOut().isEmpty() ? null : sr.carryOut();
            if (sr.lastStack() != null && !sr.lastStack().isEmpty()) {
                inheritStack[0] = sr.lastStack();
            }
        }

        // ---- 4) chunk 级清洗 ----
        DocumentCleanService.ChunkCleanResult chunkClean = service.cleanChunks(pieces);

        // ---- 5) 评判报告 ----
        printReport(pageClean, chunkClean, pieces);

        // ---- 断言：数据完整性（SUSPECT 的 chunk 既在 kept 又在 outcomes，故 kept + AUTO-DROP 数 = 分块总数） ----
        long drops = chunkClean.outcomes().stream()
                .filter(o -> o.disposition() == DocumentCleanService.Disposition.AUTO_DROP).count();
        assertEquals(pieces.size(), chunkClean.kept().size() + drops,
                "kept + AUTO-DROP 数应等于分块总数（SUSPECT 照常入库）");
        assertTrue(chunkClean.kept().size() <= pieces.size(), "kept 不应超过分块总数");
    }

    /** 按 <!-- PAGE N --> 标记拆页 */
    private static List<LlamaParseService.PageMarkdown> splitPages(String md) {
        List<LlamaParseService.PageMarkdown> pages = new ArrayList<>();
        Matcher m = PAGE_MARK.matcher(md);
        int lastEnd = 0;
        int lastPage = 0;
        while (m.find()) {
            int pageNo = Integer.parseInt(m.group(1));
            if (lastPage > 0) {
                pages.add(new LlamaParseService.PageMarkdown(lastPage, md.substring(lastEnd, m.start())));
            }
            lastEnd = m.end();
            lastPage = pageNo;
        }
        if (lastPage > 0) {
            pages.add(new LlamaParseService.PageMarkdown(lastPage, md.substring(lastEnd)));
        }
        return pages;
    }

    private static void printReport(DocumentCleanService.PageCleanResult pageClean,
                                    DocumentCleanService.ChunkCleanResult chunkClean,
                                    List<ChunkPiece> pieces) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n==================================================");
        sb.append("\nP1 清洗效果评判：ZRDDS______-Java.pdf.md");
        sb.append("\n==================================================");
        sb.append("\n[页面级] 原始页数=").append(pageClean.pages().size());
        if (pageClean.actions().isEmpty()) {
            sb.append("\n  页面级动作：无命中（该样本无传统页眉页脚/封面/目录）");
        } else {
            sb.append("\n  页面级动作：");
            for (DocumentCleanService.PageAction a : pageClean.actions()) {
                sb.append("\n    [").append(a.ruleId()).append("] 页 ").append(a.pageNum())
                        .append("：").append(a.detail());
            }
        }
        sb.append("\n[分块] chunk-size=800 / overlap=120 → ").append(pieces.size()).append(" 个 chunk");
        sb.append("\n[chunk 级清洗]");
        if (chunkClean.outcomes().isEmpty()) {
            sb.append("\n  无规则命中（全部保留）");
        } else {
            Map<String, Integer> byRule = new TreeMap<>();
            for (DocumentCleanService.CleanOutcome o : chunkClean.outcomes()) {
                byRule.merge(o.ruleId(), 1, Integer::sum);
            }
            sb.append("\n  命中统计（按规则）：");
            byRule.forEach((rule, cnt) -> sb.append("\n    ").append(rule).append(" × ").append(cnt));
            sb.append("\n  处置：AUTO-DROP ").append(chunkClean.outcomes().stream()
                            .filter(o -> o.disposition() == DocumentCleanService.Disposition.AUTO_DROP).count())
                    .append(" 个 / SUSPECT ").append(chunkClean.outcomes().stream()
                            .filter(o -> o.disposition() == DocumentCleanService.Disposition.SUSPECT).count())
                    .append(" 个");
            sb.append("\n  样例：");
            int shown = 0;
            for (DocumentCleanService.CleanOutcome o : chunkClean.outcomes()) {
                if (shown++ >= 15) {
                    break;
                }
                String content = o.piece().content() == null ? "" : o.piece().content().trim();
                String snippet = content.length() > 60 ? content.substring(0, 60) + "…" : content;
                sb.append("\n    [").append(o.ruleId()).append("/").append(o.disposition()).append("] 页 ")
                        .append(o.piece().pageNum()).append("：").append(snippet.replace("\n", "⏎"));
            }
        }
        sb.append("\n[汇总] 分块 ").append(pieces.size()).append(" → 入库 ")
                .append(chunkClean.kept().size()).append("（含 SUSPECT ").append(chunkClean.outcomes().stream()
                        .filter(o -> o.disposition() == DocumentCleanService.Disposition.SUSPECT).count())
                .append(" 个打标）/ 丢弃 ").append(chunkClean.outcomes().stream()
                        .filter(o -> o.disposition() == DocumentCleanService.Disposition.AUTO_DROP).count())
                .append(" 个");
        sb.append("\n==================================================\n");
        System.out.println(sb);
    }
}
