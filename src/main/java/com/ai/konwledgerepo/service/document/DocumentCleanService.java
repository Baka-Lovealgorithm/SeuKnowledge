package com.ai.konwledgerepo.service.document;

import com.ai.konwledgerepo.config.props.SeuDocumentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * P1 文件清洗（纯规则、零模型成本）：在解析与向量化之间拦截"确定性机械噪声"。
 * <p>
 * 两个阶段（插入点见 {@link DocumentParserService} 与 {@link DocumentParseExecutor}）：
 * <ul>
 *   <li><b>页面级 {@link #cleanPages}</b>：分块前，在 LlamaParse 逐页 markdown 上
 *       剥离页眉页脚行（P1）+ 删除首尾噪声页（P2 封面 / P3 目录 / P4 空白）；
 *       中部含标题页不整页删（P6 保护）。只剥行/删页，pageNumber 永不重排。</li>
 *   <li><b>chunk 级 {@link #cleanChunks}</b>：分块后落库前，按
 *       E 保护规则 → A 碎片 → B 图题 → C 重复 的顺序判定；处置由配置名单决定：
 *       {@code autoDropRules} → AUTO-DROP（落库 FILTERED，不进 ES）；
 *       {@code suspectRules} → SUSPECT（照常入库进 ES，仅带清洗标记）；都不在 → 仅统计不动作。</li>
 * </ul>
 * 红线（溯源保护三原则）：只删不改不合并、不重排页码、页面级整页删除仅限首尾噪声页。
 * 软删：AUTO-DROP 的 chunk 记录保留在 MySQL（历史证据"查看全文"仍可展开），仅不进 ES。
 */
@Service
public class DocumentCleanService {

    private static final Logger log = LoggerFactory.getLogger(DocumentCleanService.class);

    /** 清洗处置 */
    public enum Disposition { AUTO_DROP, SUSPECT, KEEP }

    /** chunk 级清洗决策条目（piece 与判定结果一一对应） */
    public record CleanOutcome(ChunkPiece piece, String ruleId, Disposition disposition, String reason) {
    }

    /** chunk 级清洗结果：kept 为入库列表（含 SUSPECT 标记项），outcomes 为全部判定（含 AUTO_DROP） */
    public record ChunkCleanResult(List<ChunkPiece> kept, List<CleanOutcome> outcomes) {
    }

    /** 页面级清洗动作（供日志/评判报告） */
    public record PageAction(int pageNum, String ruleId, String detail) {
    }

    /** 页面级清洗结果：pages 为清洗后列表，actions 为动作清单 */
    public record PageCleanResult(List<LlamaParseService.PageMarkdown> pages, List<PageAction> actions) {
    }

    // ---- 正则（预编译）----
    /** E2 数值键值保护：中文/字母开头 + 冒号 + 数字（"端口：3306"、"Timeout: 10"） */
    private static final Pattern KEY_VALUE = Pattern.compile("[A-Za-z\\u4e00-\\u9fa5]{2,}\\s*[：:]\\s*\\d+");
    /** A3 纯 URL */
    private static final Pattern URL_ONLY = Pattern.compile("^(https?|ftp)://\\S+|^www\\.\\S+$");
    /** B1/B2/E3 图题开头：图/Fig/表/Table + 编号 */
    private static final Pattern FIGURE_TITLE = Pattern.compile("^(图|Fig\\.?|表|Table)\\s*\\d+[.、:：\\-]?\\s*\\S+");
    /** B2 孤立表格标题 */
    private static final Pattern TABLE_TITLE = Pattern.compile("^表\\s*\\d+");
    /** P3 目录行：标题（可含空格）+ 点线 + 页码 */
    private static final Pattern TOC_LINE = Pattern.compile("^.+?\\.{2,}\\s*\\d+\\s*$");
    /** P5 尾页免责声明 */
    private static final Pattern DISCLAIMER = Pattern.compile("免责声明|版权声明|著作权|版权所有|版权归|未经许可|机密|联系方式");
    /** E4 标题行（markdown 标题 / 加粗单行） */
    private static final Pattern MARKDOWN_TITLE = Pattern.compile("^#{1,6}\\s+.+|^\\*\\*.+\\*\\*\\s*$");
    /** A5 乱码字符 */
    private static final Pattern GARBLED = Pattern.compile("[\\uFFFD\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F]");

    private final SeuDocumentProperties.Clean config;

    public DocumentCleanService(SeuDocumentProperties props) {
        this.config = props.clean();
    }

    // ==================== 页面级清洗 ====================

    /**
     * 页面级清洗：P1 页眉页脚剥离（两遍算法）→ P2/P3/P4 首尾噪声页删除（P5 仅统计）。
     * 页面顺序与 pageNumber 保持原值（不重排）。
     */
    public PageCleanResult cleanPages(List<LlamaParseService.PageMarkdown> pages) {
        if (!config.enabled() || pages == null || pages.isEmpty()) {
            return new PageCleanResult(pages == null ? List.of() : pages, List.of());
        }
        List<PageAction> actions = new ArrayList<>();
        List<LlamaParseService.PageMarkdown> result = stripHeaderFooter(pages, actions);
        result = removeNoisePages(result, actions);
        return new PageCleanResult(result, actions);
    }

    /**
     * P1 页眉页脚剥离：
     * 第一遍跨页统计归一化行（trim + 数字占位为 &lt;NUM&gt;）的出现率；
     * 第二遍剥离满足「出现率 ≥ headerFooterRatio 且 长度 ≤ headerFooterMaxLen
     * 且 位于页首/页尾 headerFooterWindow 行内」的行。只剥行不删页。
     */
    private List<LlamaParseService.PageMarkdown> stripHeaderFooter(List<LlamaParseService.PageMarkdown> pages,
                                                                   List<PageAction> actions) {
        int total = pages.size();
        List<List<String>> pageLines = new ArrayList<>(total);
        Map<String, Integer> lineCount = new HashMap<>();
        for (LlamaParseService.PageMarkdown page : pages) {
            String md = page.markdown() == null ? "" : page.markdown();
            List<String> lines = Arrays.asList(md.split("\n", -1));
            pageLines.add(lines);
            for (String line : lines) {
                String norm = normalize(line);
                if (norm.isBlank()) {
                    continue;
                }
                lineCount.merge(norm, 1, Integer::sum);
            }
        }
        // 判定页眉页脚模板行
        Set<String> templates = new HashSet<>();
        for (Map.Entry<String, Integer> e : lineCount.entrySet()) {
            if (e.getValue() * 1.0 / total >= config.headerFooterRatio()
                    && e.getKey().length() <= config.headerFooterMaxLen()) {
                templates.add(e.getKey());
            }
        }
        if (templates.isEmpty()) {
            return pages;
        }
        int window = config.headerFooterWindow();
        int strippedTotal = 0;
        List<LlamaParseService.PageMarkdown> result = new ArrayList<>(total);
        for (int i = 0; i < total; i++) {
            List<String> lines = pageLines.get(i);
            List<String> kept = new ArrayList<>(lines.size());
            for (int j = 0; j < lines.size(); j++) {
                String norm = normalize(lines.get(j));
                boolean inWindow = j < window || j >= lines.size() - window;
                if (inWindow && !norm.isBlank() && templates.contains(norm)) {
                    strippedTotal++;
                    continue;
                }
                kept.add(lines.get(j));
            }
            LlamaParseService.PageMarkdown page = pages.get(i);
            result.add(new LlamaParseService.PageMarkdown(page.pageNumber(), String.join("\n", kept), page.screenshot()));
        }
        actions.add(new PageAction(0, "P1",
                "页眉页脚剥离 " + strippedTotal + " 行（模板 " + templates.size() + " 种，出现率≥" + config.headerFooterRatio() + "）"));
        return result;
    }

    /** 归一化：trim + 数字占位（"第 3 页"→"第 &lt;NUM&gt; 页"），供页眉页脚/模板判定 */
    private static String normalize(String line) {
        return line == null ? "" : line.trim().replaceAll("\\d+", "<NUM>");
    }

    /**
     * P2/P3/P4 首尾噪声页删除（P6 中部页保护：仅第 1 页与末页参与整页删除，
     * 中部页只允许 P1 剥离）。P5 尾页免责声明命中仅统计不删除（页面级无落库对象，SUSPECT 降级）。
     */
    private List<LlamaParseService.PageMarkdown> removeNoisePages(List<LlamaParseService.PageMarkdown> pages,
                                                                  List<PageAction> actions) {
        if (pages.size() <= 1) {
            return pages; // 单页文档不删（保护内容）
        }
        List<LlamaParseService.PageMarkdown> result = new ArrayList<>(pages);
        // 首部循环删除噪声页（最多 5 页，防异常文档死循环）
        int removedHead = 0;
        while (result.size() > 1 && removedHead < 5) {
            LlamaParseService.PageMarkdown first = result.get(0);
            String md = first.markdown() == null ? "" : first.markdown().trim();
            String rule = coverOrTocOrBlank(md);
            if (rule == null) {
                break;
            }
            actions.add(new PageAction(first.pageNumber(), rule, "删除首部噪声页（内容 " + md.length() + " 字）"));
            result.remove(0);
            removedHead++;
        }
        // 尾部：末页空白删除；免责声明仅统计
        int lastIdx = result.size() - 1;
        if (lastIdx > 0) {
            LlamaParseService.PageMarkdown last = result.get(lastIdx);
            String md = last.markdown() == null ? "" : last.markdown().trim();
            if (md.trim().isEmpty()) {
                actions.add(new PageAction(last.pageNumber(), "P4", "删除尾部空白页"));
                result.remove(lastIdx);
            } else if (DISCLAIMER.matcher(md).find()) {
                actions.add(new PageAction(last.pageNumber(), "P5", "命中免责声明模式（保留待人工复核）"));
            }
        }
        return result;
    }

    /** 第 1 页判定：P4 空白（trim 后空）→ P2 封面（短且无标题/正文标点/表格）→ P3 目录（≥60% 目录行） */
    private static String coverOrTocOrBlank(String md) {
        if (md.trim().isEmpty()) {
            return "P4";
        }
        // P2 封面：≤50 字、无 markdown 标题、无正文标点（。，；）无表格、行数 ≤3
        String[] lines = md.split("\n");
        if (md.length() <= 50 && !md.contains("#") && !md.contains("。") && !md.contains("，")
                && !md.contains("|") && lines.length <= 3) {
            return "P2";
        }
        if (md.contains("目录")) {
            int nonBlank = 0;
            int toc = 0;
            for (String line : lines) {
                String t = line.trim();
                if (t.isEmpty()) {
                    continue;
                }
                nonBlank++;
                if (TOC_LINE.matcher(t).matches() || (t.startsWith("-") && t.matches(".*\\d+\\s*$"))) {
                    toc++;
                }
            }
            if (nonBlank > 0 && toc * 1.0 / nonBlank >= 0.6) {
                return "P3";
            }
        }
        return null;
    }

    // ==================== chunk 级清洗 ====================

    /**
     * chunk 级清洗：E 保护规则先行（命中放行），再依次判定 A 碎片 / B 图题 / C 重复。
     * 处置由配置名单决定（见类注释），返回 kept（入库）+ outcomes（全部判定）。
     */
    public ChunkCleanResult cleanChunks(List<ChunkPiece> pieces) {
        if (!config.enabled() || pieces == null || pieces.isEmpty()) {
            return new ChunkCleanResult(pieces == null ? List.of() : pieces, List.of());
        }
        List<ChunkPiece> kept = new ArrayList<>();
        List<CleanOutcome> outcomes = new ArrayList<>();
        for (int i = 0; i < pieces.size(); i++) {
            ChunkPiece piece = pieces.get(i);
            String ruleId = evaluate(piece, pieces, i);
            if (ruleId == null) {
                kept.add(piece);
                continue;
            }
            Disposition d = dispositionOf(ruleId);
            CleanOutcome outcome = new CleanOutcome(piece, ruleId, d, reasonOf(ruleId, piece));
            outcomes.add(outcome);
            if (d == Disposition.SUSPECT || d == Disposition.KEEP) {
                kept.add(piece); // SUSPECT 照常入库（仅带标记），KEEP 仅统计
            }
            // AUTO_DROP 不进 kept
        }
        return new ChunkCleanResult(kept, outcomes);
    }

    /** 判定一条 chunk 命中的规则；返回 null 表示保留（未命中或被保护规则放行） */
    private String evaluate(ChunkPiece piece, List<ChunkPiece> all, int index) {
        String content = piece.content();
        if (content == null) {
            return "A1";
        }
        String trimmed = content.trim();
        // ---- E 保护规则（命中即放行）----
        if (isTableContent(piece, content)) {
            return null; // E1
        }
        if (KEY_VALUE.matcher(content).find()) {
            return null; // E2
        }
        if (isFigureWithContent(trimmed)) {
            return null; // E3 图题 + 长内容（视觉内容本体）
        }
        if (isTitleLine(piece, trimmed)) {
            return null; // E4
        }
        if (isOverlapWithNeighbor(all, index)) {
            return null; // E5 跨页 carry 重叠产物
        }
        // ---- A 碎片 ----
        if (trimmed.isEmpty()) {
            return "A1";
        }
        if (isSymbolsOnly(trimmed)) {
            return "A2";
        }
        if (URL_ONLY.matcher(trimmed).matches()) {
            return "A3";
        }
        if (trimmed.length() < config.shortMinLen() && (containsNoChinese(trimmed) || containsNoDigit(trimmed))) {
            return "A4";
        }
        if (GARBLED.matcher(trimmed).find()) {
            return "A5";
        }
        // ---- B 图题 ----
        if (TABLE_TITLE.matcher(trimmed).find() && trimmed.length() <= 20 && !content.contains("|")) {
            return "B2"; // 孤立表格标题（无表格行）
        }
        if (FIGURE_TITLE.matcher(trimmed).find() && trimmed.length() <= config.figureTitleMaxLen()) {
            return "B1"; // 孤立图题
        }
        if (isImageOnly(content)) {
            return "B3";
        }
        // ---- C 重复 ----
        if (templateHit(content, all, index)) {
            return "C1"; // 页眉页脚模板（保留首个）
        }
        if (nearDuplicate(content, all, index)) {
            return "C2"; // 同文档内近重复（保留首个）
        }
        if (longDuplicate(content, all, index)) {
            return "C3"; // 跨 chunk 长重复（保留首个）
        }
        return null;
    }

    /** E1 表格内容保护：markdown 表格（含 | 分隔符）或 Excel 分块（title 含 sheet/表格） */
    private static boolean isTableContent(ChunkPiece piece, String content) {
        if (content.contains("|")) {
            return true;
        }
        String title = piece.title();
        return title != null && (title.contains("sheet") || title.contains("Sheet") || title.contains("表格"));
    }

    /** E3 图题 + 内容：图题开头且长度达到阈值（视为视觉内容本体，放行） */
    private boolean isFigureWithContent(String trimmed) {
        return FIGURE_TITLE.matcher(trimmed).find() && trimmed.length() >= config.figureContentMinLen();
    }

    /** E4 标题行：content 即标题（title 相同）或 markdown 标题/加粗单行 */
    private static boolean isTitleLine(ChunkPiece piece, String trimmed) {
        String title = piece.title();
        if (title != null && !title.isBlank() && trimmed.equals(title)) {
            return true;
        }
        if (MARKDOWN_TITLE.matcher(trimmed).matches() && !trimmed.contains("\n")) {
            return true;
        }
        return false;
    }

    /** E5 跨页 carry 重叠保护：相邻 chunk 首尾有 ≥40 字符的<b>真前缀/真后缀</b>重叠（splitWithCarry 有意产物）。
     * 完全相同的相邻 chunk 不视为 overlap（prevTail 长度 == 本 chunk 长度），交由 C1/C2 重复规则处理。 */
    private static boolean isOverlapWithNeighbor(List<ChunkPiece> all, int index) {
        String c = all.get(index).content();
        if (index > 0) {
            String prev = all.get(index - 1).content();
            String prevTail = tail(prev, 40);
            if (!prevTail.isEmpty() && prevTail.length() < c.length() && c.startsWith(prevTail)) {
                return true;
            }
        }
        if (index < all.size() - 1) {
            String next = all.get(index + 1).content();
            String cTail = tail(c, 40);
            if (!cTail.isEmpty() && cTail.length() < next.length() && next.startsWith(cTail)) {
                return true;
            }
        }
        return false;
    }

    private static String tail(String s, int n) {
        return s.substring(Math.max(0, s.length() - n));
    }

    /** A2 纯符号：无中文/数字/字母，仅标点/装饰字符（逐字符判定，兼容多行 chunk） */
    private static boolean isSymbolsOnly(String trimmed) {
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (Character.isLetterOrDigit(c) || (c >= 0x4E00 && c <= 0x9FA5)) {
                return false;
            }
        }
        return true;
    }

    /** B3 纯图片引用：仅含 markdown 图片或 HTML img 占位 */
    private static boolean isImageOnly(String content) {
        String t = content.trim();
        if (t.isEmpty()) {
            return false;
        }
        String noImg = t.replaceAll("!\\[[^]]*]\\([^)]*\\)", "").replaceAll("<img[^>]*>", "").trim();
        return noImg.isEmpty();
    }

    /** C1 页眉页脚模板：归一化文本出现于 ≥ templateRatio 的 chunk，且非首个出现（保留首个） */
    private boolean templateHit(String content, List<ChunkPiece> all, int index) {
        String norm = normalize(content);
        if (norm.isEmpty()) {
            return false;
        }
        int n = all.size();
        int count = 0;
        int firstOccur = -1;
        for (int i = 0; i < n; i++) {
            if (normalize(all.get(i).content()).equals(norm)) {
                count++;
                if (firstOccur < 0) {
                    firstOccur = i;
                }
            }
        }
        return count * 1.0 / n >= config.templateRatio() && firstOccur != index;
    }

    /** C2 近重复：与之前的 chunk 3-gram Jaccard ≥ nearDupSim（长度差 &gt;30% 快速跳过） */
    private boolean nearDuplicate(String content, List<ChunkPiece> all, int index) {
        Set<String> grams = trigrams(content);
        if (grams.isEmpty()) {
            return false;
        }
        for (int j = 0; j < index; j++) {
            String other = all.get(j).content();
            if (Math.abs(content.length() - other.length()) * 10 > content.length() * 3) {
                continue;
            }
            Set<String> otherGrams = trigrams(other);
            if (otherGrams.isEmpty()) {
                continue;
            }
            int inter = 0;
            for (String g : grams) {
                if (otherGrams.contains(g)) {
                    inter++;
                }
            }
            int union = grams.size() + otherGrams.size() - inter;
            if (union > 0 && inter * 1.0 / union >= config.nearDupSim()) {
                return true;
            }
        }
        return false;
    }

    /** 3-gram 集合（按字符滑动，中文按字符粒度） */
    private static Set<String> trigrams(String s) {
        if (s == null || s.length() < 3) {
            return Set.of();
        }
        Set<String> grams = new LinkedHashSet<>();
        for (int i = 0; i + 3 <= s.length(); i++) {
            grams.add(s.substring(i, i + 3));
        }
        return grams;
    }

    /** C3 跨 chunk 长重复：本 chunk 存在 ≥ longDupMinLen 的完整行出现在之前的 chunk 中 */
    private boolean longDuplicate(String content, List<ChunkPiece> all, int index) {
        for (String line : content.split("\n")) {
            String t = line.trim();
            if (t.length() < config.longDupMinLen()) {
                continue;
            }
            for (int j = 0; j < index; j++) {
                if (all.get(j).content().contains(t)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 处置名单：autoDropRules → AUTO_DROP；suspectRules → SUSPECT；都不在 → KEEP（fail-safe） */
    private Disposition dispositionOf(String ruleId) {
        List<String> auto = config.autoDropRules();
        if (auto != null && auto.contains(ruleId)) {
            return Disposition.AUTO_DROP;
        }
        List<String> suspect = config.suspectRules();
        if (suspect != null && suspect.contains(ruleId)) {
            return Disposition.SUSPECT;
        }
        return Disposition.KEEP;
    }

    /** 清洗原因文案（写入 clean_reason，供人工复核展示） */
    private String reasonOf(String ruleId, ChunkPiece piece) {
        String content = piece.content();
        String snippet = content == null ? "" : content.trim();
        if (snippet.length() > 30) {
            snippet = snippet.substring(0, 30) + "…";
        }
        return switch (ruleId) {
            case "A1" -> "A1 空白碎片";
            case "A2" -> "A2 纯符号碎片";
            case "A3" -> "A3 纯 URL";
            case "A4" -> "A4 超短碎片（<" + config.shortMinLen() + " 字且无中文/无数字）";
            case "A5" -> "A5 OCR 乱码";
            case "B1" -> "B1 孤立图题（≤" + config.figureTitleMaxLen() + " 字）";
            case "B2" -> "B2 孤立表格标题";
            case "B3" -> "B3 纯图片引用";
            case "C1" -> "C1 页眉页脚模板（出现率≥" + config.templateRatio() + "，保留首个）";
            case "C2" -> "C2 近重复（3-gram Jaccard≥" + config.nearDupSim() + "，保留首个）";
            case "C3" -> "C3 跨 chunk 长重复（≥" + config.longDupMinLen() + " 字，保留首个）";
            default -> ruleId;
        } + "：「" + snippet + "」";
    }

    private static boolean containsNoChinese(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FA5) {
                return false;
            }
        }
        return true;
    }

    private static boolean containsNoDigit(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
