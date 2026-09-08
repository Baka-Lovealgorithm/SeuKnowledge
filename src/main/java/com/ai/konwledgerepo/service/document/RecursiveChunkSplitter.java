package com.ai.konwledgerepo.service.document;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 递归分词器（仿 LlamaIndex RecursiveCharacterTextSplitter，面向 LlamaParse 输出的 Markdown）。
 *
 * <p>策略：结构识别是<b>行级优先级认领的词法器</b>——先由 {@link CodeExtractor} 把围栏代码段圈走
 * （小代码块整块原子、大代码块行组+重复围栏头，见 {@link #codePieces}；围栏内 {@code |---|} 不被误判为表格、
 * {@code # 注释} 不被误判为标题），剩余正文再由 {@link TableExtractor} 切成"正文段 + 表格段"——
 * 正文段按分隔符优先级逐层切分（Markdown 二级标题 → 三级标题 → … → 一级标题 → 空行（段落）→ 换行 →
 * 句子标点 → 空格 → 字符兜底）；表格段经 forward-fill 规整化后按 A+B 混合产出（见 {@link #tablePieces}）。
 * 输出块携带标题祖先链路径（ChunkPiece.title，见 {@link Headings}），供引用展示与向量/重排感知结构；
 * 代码/表格段旁路窗口直出且不更新标题栈（栈在结构块内冻结）。
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

        // 1) 结构认领（行级词法器）：代码围栏段优先圈走，剩余正文再进表格识别
        String full = carry.isEmpty() ? text : carry + "\n" + text;
        List<CodeExtractor.Segment> topSegments = CodeExtractor.extract(full);

        // 2) 逐段处理：正文段进窗口（标题栈跟踪），表格段与代码段旁路窗口直出（标题栈冻结）
        List<ChunkPiece> pieces = new ArrayList<>();
        StringBuilder window = new StringBuilder();
        List<Headings.StackEntry> prevStack = inheritStack == null ? new ArrayList<>() : new ArrayList<>(inheritStack);
        for (CodeExtractor.Segment top : topSegments) {
            if (top instanceof CodeExtractor.CodeBlock code) {
                prevStack = flush(pieces, window, ov, prevStack, pageNum);
                pieces.addAll(codePieces(code, max, prevStack, pageNum));
                continue;
            }
            if (!(top instanceof CodeExtractor.ProseBlock prose)) {
                continue;
            }
            for (TableExtractor.Segment seg : TableExtractor.extract(prose.text())) {
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
        }
        prevStack = flush(pieces, window, ov, prevStack, pageNum);
        return new SplitResult(pieces, window.toString().trim(), prevStack);
    }

    /**
     * 表格块产出（A+B 混合）：
     * 小表（回填后 ≤ chunk-size）整表一个原子块；大表按行组分块（每组带 caption（若有）+表头+分隔行，组间无 overlap）。
     * 表格块 title = 祖先链路径；不更新标题栈（表格不产生新章节）。
     */
    static List<ChunkPiece> tablePieces(TableExtractor.Table table, int chunkSize,
                                        List<Headings.StackEntry> stack, int pageNum) {
        String title = Headings.path(stack);
        StringBuilder head = new StringBuilder();
        if (table.caption() != null && !table.caption().isBlank()) {
            head.append(table.caption()).append('\n');
        }
        head.append("| ").append(String.join(" | ", table.header())).append(" |\n");
        head.append("| ").append(String.join(" | ", table.header().stream().map(h -> "---").toList())).append(" |\n");
        String headBlock = head.toString();
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

    /**
     * 代码块产出（A+B 混合，与 {@link #tablePieces} 同构）：
     * 小代码块（整块含围栏 ≤ chunk-size）一个原子块；大代码块按<b>行组</b>切——
     * 每组重复开栏行（带语言标签）与闭栏行，组界优先回退到组内最后一个空行（对齐 import/方法等
     * 空行块边界），回退不到再按行界；组间无 overlap（围栏头 + title 祖先链即锚点，
     * 字符级重叠对代码是截半行噪音）；单个超上限的行整行独占一组（同表格超大行例外）。
     * 代码体行逐字保留、一字不改；仅组边界丢弃纯空行。
     * 代码块 title = 祖先链路径；不更新标题栈（围栏内 {@code #} 注释行不当作标题）。
     * 未闭合围栏（closed=false）逐组合成闭栏自愈。全空白代码块不产出。
     */
    static List<ChunkPiece> codePieces(CodeExtractor.CodeBlock block, int chunkSize,
                                       List<Headings.StackEntry> stack, int pageNum) {
        List<ChunkPiece> out = new ArrayList<>();
        List<String> lines = block.lines();
        boolean anyContent = false;
        for (String l : lines) {
            if (!l.isBlank()) {
                anyContent = true;
                break;
            }
        }
        if (!anyContent) {
            return out;
        }
        String title = Headings.path(stack);
        String head = block.opener() + "\n";
        String tail = "\n" + block.marker();
        if (block.toMarkdown().length() <= chunkSize) {
            // A：整块原子
            out.add(new ChunkPiece(block.toMarkdown(), pageNum, title));
            return out;
        }
        // B：行组（溢出时优先回退到最后空行，每组重复围栏头尾）
        int groupMax = Math.max(1, chunkSize - head.length() - tail.length());
        int n = lines.size();
        int start = 0;
        while (start < n) {
            while (start < n && lines.get(start).isBlank()) {
                start++; // 组首空行无信息量，丢弃
            }
            if (start >= n) {
                break;
            }
            int end = start;
            int len = 0;
            int lastBlank = -1; // 组内最后一个空行（首选组界）
            boolean overflowed = false;
            while (end < n) {
                int add = lines.get(end).length() + 1;
                if (end > start && len + add > groupMax) {
                    overflowed = true;
                    break;
                }
                if (lines.get(end).isBlank()) {
                    lastBlank = end;
                }
                len += add;
                end++;
            }
            if (overflowed && lastBlank > start) {
                end = lastBlank;
            }
            int cut = end;
            while (cut > start && lines.get(cut - 1).isBlank()) {
                cut--; // 去掉组尾空行
            }
            if (cut <= start) {
                cut = start + 1; // 兜底：每组至少 1 行，防空转
            }
            String body = String.join("\n", lines.subList(start, cut));
            out.add(new ChunkPiece(head + body.stripTrailing() + tail, pageNum, title));
            start = cut;
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
