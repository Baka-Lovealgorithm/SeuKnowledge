package com.ai.konwledgerepo.service.document;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 递归分词器（仿 LlamaIndex RecursiveCharacterTextSplitter，面向 LlamaParse 输出的 Markdown）。
 *
 * <p>策略：先由 {@link TableExtractor} 把文本切成"正文段 + 表格段"——正文段按分隔符优先级逐层切分
 * （Markdown 二级标题 → 三级标题 → … → 一级标题 → 空行（段落）→ 换行 → 句子标点 → 空格 → 字符兜底）；
 * 表格段经 forward-fill 规整化后按 A+B 混合产出：小表整表原子块、大表行组+表头（见 {@link #tablePieces}）。
 * 输出块携带标题祖先链路径（ChunkPiece.title，见 {@link Headings}），供引用展示与向量/重排感知结构。
 *
 * <p>参数沿用既有分块配置（chunk-size / chunk-overlap）；跨页 carry 语义与 {@link ChunkSplitter#splitWithCarry}
 * 一致：页尾重叠文本并入下一页首个真实片段，下一页以标题行开头时丢弃 carry（章节边界无需衔接）；
 * 标题栈同样跨页继承（inheritStack）。
 */
public final class RecursiveChunkSplitter {

    /** 默认块长上限（字符） */
    public static final int DEFAULT_MAX = 800;

    /** 分隔符优先级（高 → 低）；空串兜底按字符硬切 */
    private static final String[] SEPARATORS = {
            "\n## ", "\n### ", "\n#### ", "\n##### ", "\n###### ",
            "\n# ",
            "\n\n", "\n",
            "。", "！", "？", "；", ";", ".", "!", "?",
            " ", ""
    };

    private static final Pattern FIRST_LINE = Pattern.compile("^[\\s\\uFEFF]*([^\\n]+)");

    private RecursiveChunkSplitter() {
    }

    /**
     * 跨页分块结果：块列表 + 页尾重叠文本（供下一页作为 carry 延续）+ 页内最后标题栈（供下一页继承）。
     */
    public record SplitResult(List<ChunkPiece> pieces, String carryOut, List<Headings.StackEntry> lastStack) {
        public SplitResult(List<ChunkPiece> pieces, String carryOut) {
            this(pieces, carryOut, null);
        }
    }

    public static List<ChunkPiece> split(String text, int pageNum) {
        return split(text, pageNum, DEFAULT_MAX, 0);
    }

    public static List<ChunkPiece> split(String text, int pageNum, int chunkSize, int overlap) {
        return splitWithCarry(text, pageNum, chunkSize, overlap, null).pieces();
    }

    /**
     * 跨页递归分块：carryIn 为上一页尾部重叠文本，并入本页首个真实片段；
     * 本页以标题行开头时丢弃 carryIn。返回本页最后一个块的尾部重叠文本（carryOut）。
     */
    public static SplitResult splitWithCarry(String text, int pageNum, int chunkSize, int overlap, String carryIn) {
        return splitWithCarry(text, pageNum, chunkSize, overlap, carryIn, null);
    }

    /**
     * 跨页递归分块：carryIn 为上一页尾部重叠文本，并入本页首个真实片段；
     * 本页以标题行开头时丢弃 carryIn。inheritStack 为上一页末标题栈，供本页无标题块继承。
     * 返回本页最后一个块的尾部重叠文本（carryOut）与页内最后标题栈（lastStack）。
     */
    public static SplitResult splitWithCarry(String text, int pageNum, int chunkSize, int overlap,
                                             String carryIn, List<Headings.StackEntry> inheritStack) {
        if (text == null || text.isBlank()) {
            // 空页：carry 与标题栈透传，不打断上下文流
            return new SplitResult(new ArrayList<>(), carryIn == null ? "" : carryIn, inheritStack);
        }
        if (text.charAt(0) == '\uFEFF') {
            text = text.substring(1);
        }
        int max = chunkSize <= 0 ? DEFAULT_MAX : chunkSize;
        int ov = Math.max(0, Math.min(overlap, max / 2));

        // 本页以标题行开头：丢弃跨页重叠（章节边界无需衔接）
        String carry = (carryIn == null || carryIn.isBlank()) ? "" : carryIn.trim();
        if (!carry.isEmpty() && startsWithHeading(text)) {
            carry = "";
        }

        // 1) 表格段分类：pipe 表格（含 forward-fill 规整化）作为原子段直接产出，正文段递归切分
        String full = carry.isEmpty() ? text : carry + "\n" + text;
        List<TableExtractor.Segment> segments = TableExtractor.extract(full);

        // 2) 逐段处理：正文段进窗口（标题栈跟踪），表格段直接产出原子块/行组
        List<ChunkPiece> pieces = new ArrayList<>();
        StringBuilder window = new StringBuilder();
        List<Headings.StackEntry> prevStack = inheritStack == null ? new ArrayList<>() : new ArrayList<>(inheritStack);
        for (TableExtractor.Segment seg : segments) {
            if (seg instanceof TableExtractor.TextBlock tb) {
                List<String> segs = new ArrayList<>();
                splitRecursive(tb.text(), SEPARATORS, 0, max, segs);
                for (String s : segs) {
                    if (window.length() > 0 && window.length() + s.length() > max) {
                        prevStack = flush(pieces, window, ov, prevStack, pageNum);
                    }
                    window.append(s).append('\n');
                }
            } else if (seg instanceof TableExtractor.TableBlock tbl) {
                prevStack = flush(pieces, window, ov, prevStack, pageNum);
                pieces.addAll(tablePieces(tbl.table(), max, prevStack, pageNum));
            }
        }
        prevStack = flush(pieces, window, ov, prevStack, pageNum);
        return new SplitResult(pieces, window.toString().trim(), prevStack);
    }

    /**
     * 表格块产出（A+B 混合）：
     * 小表（回填后 ≤ chunk-size）整表一个原子块；大表按行组分块（每组带表头+分隔行，组间无 overlap）。
     * 表格块 title = 祖先链路径；不更新标题栈（表格不产生新章节）。
     */
    static List<ChunkPiece> tablePieces(TableExtractor.Table table, int chunkSize,
                                        List<Headings.StackEntry> stack, int pageNum) {
        String title = Headings.path(stack);
        String headBlock = "| " + String.join(" | ", table.header()) + " |\n"
                + "| " + String.join(" | ", table.header().stream().map(h -> "---").toList()) + " |\n";
        List<ChunkPiece> out = new ArrayList<>();
        if (table.toMarkdown().length() <= chunkSize) {
            // A：整表原子块
            out.add(new ChunkPiece(table.toMarkdown(), pageNum, title));
            return out;
        }
        // B：大表行组
        StringBuilder group = new StringBuilder(headBlock);
        for (List<String> row : table.rows()) {
            String rowLine = "| " + String.join(" | ", row) + " |";
            if (group.length() > headBlock.length() && group.length() + rowLine.length() + 1 > chunkSize) {
                out.add(new ChunkPiece(group.toString().trim(), pageNum, title));
                group.setLength(0);
                group.append(headBlock);
            }
            group.append(rowLine).append('\n');
        }
        if (group.length() > headBlock.length()) {
            out.add(new ChunkPiece(group.toString().trim(), pageNum, title));
        }
        return out;
    }

    /** 递归切分：选当前文本中优先级最高的分隔符切分，超长片段用更低优先级分隔符继续递归 */
    private static void splitRecursive(String text, String[] seps, int sepIdx, int max, List<String> out) {
        if (text == null || text.isBlank()) {
            return;
        }
        String trimmed = text.trim();
        if (trimmed.length() <= max) {
            out.add(trimmed);
            return;
        }
        String chosen = null;
        int nextIdx = sepIdx;
        for (int i = sepIdx; i < seps.length; i++) {
            String s = seps[i];
            if (s.isEmpty()) {
                chosen = "";
                nextIdx = i + 1;
                break;
            }
            if (trimmed.contains(s)) {
                chosen = s;
                nextIdx = i + 1;
                break;
            }
        }
        if (chosen == null) {
            hardSplit(trimmed, max, out);
            return;
        }
        List<String> parts = splitBy(trimmed, chosen);
        for (String part : parts) {
            if (part.isBlank()) {
                continue;
            }
            if (part.trim().length() <= max) {
                out.add(part.trim());
            } else {
                splitRecursive(part, seps, nextIdx, max, out);
            }
        }
    }

    /** 按分隔符切分；空串分隔符按字符块切（兜底） */
    private static List<String> splitBy(String text, String separator) {
        List<String> parts = new ArrayList<>();
        if (separator.isEmpty()) {
            return parts; // 由 hardSplit 兜底，避免无限递归
        }
        int start = 0;
        int idx;
        while ((idx = text.indexOf(separator, start)) >= 0) {
            if (idx > start) {
                parts.add(text.substring(start, idx));
            }
            start = idx + separator.length();
        }
        if (start < text.length()) {
            parts.add(text.substring(start));
        }
        return parts;
    }

    /** 无分隔符可用时的字符级硬切（≤ max） */
    private static void hardSplit(String text, int max, List<String> out) {
        for (int i = 0; i < text.length(); i += max) {
            String seg = text.substring(i, Math.min(i + max, text.length())).trim();
            if (!seg.isEmpty()) {
                out.add(seg);
            }
        }
    }

    /**
     * 输出当前窗口为一块，并保留尾部 overlap 字符作为下一块前缀。
     * 块标题：按行扫描块内所有标题（见 {@link Headings}）更新祖先栈，
     * 块 title = 栈的完整路径（无标题块继承前置栈）；返回块内最后栈供后续块/下页继承。
     *
     * @return 供后续块继承的标题栈
     */
    private static List<Headings.StackEntry> flush(List<ChunkPiece> result, StringBuilder window, int overlap,
                                                   List<Headings.StackEntry> prevStack, int pageNum) {
        if (window.length() == 0) {
            return prevStack;
        }
        String block = window.toString().trim();
        List<Headings.StackEntry> stack = prevStack == null ? new ArrayList<>() : new ArrayList<>(prevStack);
        if (!block.isEmpty()) {
            for (String line : block.split("\\r?\\n")) {
                String t = line.trim();
                if (t.isEmpty()) {
                    continue;
                }
                Headings.Heading h = Headings.parse(t);
                if (h != null) {
                    stack = Headings.apply(stack, h);
                }
            }
            result.add(new ChunkPiece(block, pageNum, Headings.path(stack)));
        }
        String tail = "";
        if (overlap > 0 && window.length() > overlap) {
            tail = window.substring(window.length() - overlap);
        }
        window.setLength(0);
        if (!tail.isEmpty()) {
            window.append(tail).append('\n');
        }
        return List.copyOf(stack);
    }

    /** 文本首个非空行是否为标题行（标题开头时丢弃跨页 carry） */
    private static boolean startsWithHeading(String text) {
        Matcher m = FIRST_LINE.matcher(text);
        if (!m.find()) {
            return false;
        }
        return Headings.parse(m.group(1).trim()) != null;
    }
}
