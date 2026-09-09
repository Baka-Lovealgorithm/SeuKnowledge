package com.ai.konwledgerepo.service.document;

import java.util.ArrayList;
import java.util.List;

/**
 * 代码围栏提取器（面向 LlamaParse 产物与 .md 直传的 Markdown）。
 *
 * <p>职责：把 md 文本按行切成"非围栏正文段（{@link ProseBlock}）+ 围栏代码段（{@link CodeBlock}）"的有序列表。
 * 正文段交回 {@link TableExtractor} 做表格识别；代码段由
 * {@link RecursiveChunkSplitter#codePieces} 原子/行组直出，绕过递归切分与标题栈更新。
 *
 * <p>与表格识别的共存方式是<b>行级优先级认领</b>（词法器）：围栏先于表格圈走自己的行段，
 * 因此围栏内的 {@code |---|}（ASCII 表/配置示例）不会被误判为表格而 forward-fill 注入原文没有的内容，
 * 围栏内的 {@code # 注释} 行不会被误判为标题污染祖先链；反之，真实管道表是单行结构，
 * 不可能包含行首围栏，表格识别不受围栏规则影响。
 *
 * <p>识别规则（CommonMark 子集，单遍行状态机）：
 * <ul>
 *   <li><b>开栏</b>：trim 后以 ≥3 个连续反引号或波浪线开始，其后残余为 info（语言标签，trim 后保存）；
 *       反引号围栏的 info 含反引号时不构成开栏（CommonMark 约束）；</li>
 *   <li><b>闭栏</b>：trim 后仅由同一种围栏字符组成、且数量不少于开栏（带 info 的围栏行在围栏内视为普通代码行）；</li>
 *   <li><b>围栏内行原样保留</b>（含空行、{@code |} 行、{@code #} 行，不 trim 不改写）；</li>
 *   <li><b>未闭合围栏</b>（PDF/LlamaParse 逐页产物页尾常见）：照常产出代码段并置 {@code closed=false}，
 *       分块侧逐组合成闭栏自愈，绝不吞围栏之后的正文。</li>
 * </ul>
 *
 * <p>初步版限制（有意为之）：不识别 4 空格缩进代码块（中文文档误伤风险高）；围栏状态不跨页延续
 * （LlamaParse 把一块代码分到两页时，各页独立成块，由 chunk title 的祖先链提供跨块语义衔接）。
 */
public final class CodeExtractor {

    private CodeExtractor() {
    }

    /** 段类型：非围栏正文或围栏代码（互斥，按行覆盖原文） */
    public sealed interface Segment permits ProseBlock, CodeBlock {
    }

    /** 非围栏正文段：继续走表格识别 + 递归切分 */
    public record ProseBlock(String text) implements Segment {
    }

    /**
     * 围栏代码段。
     *
     * @param marker 围栏标记（开栏的连续反引号/波浪线原样，如 {@code ```}、{@code ~~~~}），每组输出时重复
     * @param info   语言标签（开栏行围栏符之后的内容，trim 后；可为空串）
     * @param lines  围栏内代码原文行（逐行保留，不 trim；不含开栏/闭栏行）
     * @param closed 源文本中是否正确闭合（false = 页尾遗留未闭合，输出时合成闭栏）
     */
    public record CodeBlock(String marker, String info, List<String> lines, boolean closed) implements Segment {

        /** 开栏行（```java / ~~~sh 形式），行组输出时逐组重复 */
        public String opener() {
            return info == null || info.isBlank() ? marker : marker + info;
        }

        /** 完整围栏代码 Markdown（开栏行 + 原文体 + 闭栏行），供小代码块原子成块 */
        public String toMarkdown() {
            StringBuilder sb = new StringBuilder();
            sb.append(opener()).append('\n');
            for (String line : lines) {
                sb.append(line).append('\n');
            }
            sb.append(marker);
            return sb.toString().trim();
        }
    }

    /**
     * 按行切分：返回正文段与代码段的有序列表（源顺序；空正文段丢弃；空白行保留在所属段内）。
     */
    public static List<Segment> extract(String text) {
        List<Segment> segments = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return segments;
        }
        String[] lines = text.split("\\r?\\n", -1);
        List<String> prose = new ArrayList<>();
        int i = 0;
        while (i < lines.length) {
            String[] fence = matchFenceOpen(lines[i].trim());
            if (fence == null) {
                prose.add(lines[i]);
                i++;
                continue;
            }
            flushProse(prose, segments);
            String marker = fence[0];
            String info = fence[1];
            char fc = marker.charAt(0);
            int minClose = marker.length();
            List<String> body = new ArrayList<>();
            boolean closed = false;
            i++;
            while (i < lines.length) {
                String t = lines[i].trim();
                if (isFenceClose(t, fc, minClose)) {
                    closed = true;
                    i++;
                    break;
                }
                body.add(lines[i]);
                i++;
            }
            segments.add(new CodeBlock(marker, info, body, closed));
        }
        flushProse(prose, segments);
        return segments;
    }

    /**
     * 开栏行识别：trim 后以 ≥3 连续 {@code '} 反引号或 {@code ~} 波浪线开始。
     * 返回 {@code [围栏标记, info]}；非开栏返回 null。反引号围栏 info 含反引号时不构成开栏。
     */
    private static String[] matchFenceOpen(String trimmed) {
        if (trimmed.length() < 3) {
            return null;
        }
        char fc = trimmed.charAt(0);
        if (fc != '`' && fc != '~') {
            return null;
        }
        int run = 0;
        while (run < trimmed.length() && trimmed.charAt(run) == fc) {
            run++;
        }
        if (run < 3) {
            return null;
        }
        String info = trimmed.substring(run).trim();
        if (fc == '`' && info.indexOf('`') >= 0) {
            return null;
        }
        return new String[]{trimmed.substring(0, run), info};
    }

    /** 闭栏行：trim 后仅由同一围栏字符组成且数量 ≥ 开栏数 */
    private static boolean isFenceClose(String trimmed, char fenceChar, int minLen) {
        if (trimmed.length() < minLen) {
            return false;
        }
        for (int i = 0; i < trimmed.length(); i++) {
            if (trimmed.charAt(i) != fenceChar) {
                return false;
            }
        }
        return true;
    }

    /** 正文行累积 → 正文段（trim 后空白则丢弃） */
    private static void flushProse(List<String> proseLines, List<Segment> segments) {
        if (!proseLines.isEmpty()) {
            String text = String.join("\n", proseLines).trim();
            if (!text.isEmpty()) {
                segments.add(new ProseBlock(text));
            }
            proseLines.clear();
        }
    }
}
