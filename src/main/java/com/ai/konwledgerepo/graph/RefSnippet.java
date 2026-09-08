package com.ai.konwledgerepo.graph;

import java.util.ArrayList;
import java.util.List;

/**
 * 引用 snippet 截断工具：为前端 refs 卡片生成 chunk 原文片段。
 * <p>
 * 结构块感知：截断点落在围栏代码块（```/~~~ 开始，闭栏行或文本尾结束）或
 * markdown 表格块（连续以 {@code |} 开头的行序列）内时，整块保留
 * （保证前端按 GFM 渲染成完整代码块/表格，而非被拦腰切断的残缺行）。
 * 围栏优先级高于表格：围栏内的 {@code |} 行不视为表格。
 * 非结构内容按普通字符截断（超出加省略号）。
 * <p>
 * 普通截断上限 {@value #MAX} 字符；结构块整块保留后最大约等于 chunk 上限（默认 800 字），
 * 前端可展开全文，故 snippet 略超上限可接受。
 */
public final class RefSnippet {

    /** snippet 截断上限（字符） */
    public static final int MAX = 300;

    private RefSnippet() {
    }

    /**
     * 结构块感知截断：content 为 null 返回空串；长度 ≤ {@link #MAX} 原样返回；
     * 截断点落在围栏代码块或表格块内 → 返回该块完整文本；否则普通截断 + 省略号。
     */
    public static String snippet(String content) {
        return snippet(content, MAX);
    }

    /** 指定上限版本（测试与扩展用），语义同 {@link #snippet(String)} */
    static String snippet(String content, int max) {
        if (content == null) {
            return "";
        }
        if (max <= 0 || content.length() <= max) {
            return content;
        }
        StructSpan hit = findStructuralSpanAt(content, max);
        if (hit != null) {
            return content.substring(hit.start, hit.end);
        }
        return content.substring(0, max) + "…";
    }

    /** 结构块（围栏代码块/表格块）区间 [start, end)，start/end 均为行边界偏移（end 含行尾换行，便于 trim 后干净展示） */
    private record StructSpan(int start, int end) {
    }

    /**
     * 查找覆盖第 offset 个字符（0 起，即截断点：substring(0, offset) 被保留）的结构块。
     * 单遍行扫描：围栏代码块优先认领（开栏 trim 后以 ≥3 个 {@code `} 或 {@code ~} 开始，
     * 闭栏为纯同围栏字符行；未闭合延伸至文本尾）；非围栏区内的连续 {@code |} 行序列视为表格块。
     * 返回 null 表示 offset 不在任何结构块内。
     */
    private static StructSpan findStructuralSpanAt(String content, int offset) {
        List<StructSpan> blocks = new ArrayList<>();
        int lineStart = 0;
        boolean inFence = false;
        char fenceChar = 0;
        boolean inTable = false;
        int blockStart = 0;
        while (true) {
            int lineEnd = content.indexOf('\n', lineStart);
            boolean hasNl = lineEnd >= 0;
            if (lineEnd < 0) {
                lineEnd = content.length();
            }
            String line = content.substring(lineStart, lineEnd).trim();
            if (inFence) {
                if (isFenceCloseLine(line, fenceChar)) {
                    blocks.add(new StructSpan(blockStart, hasNl ? lineEnd + 1 : lineEnd));
                    inFence = false;
                }
            } else if (isFenceOpenLine(line)) {
                if (inTable) {
                    blocks.add(new StructSpan(blockStart, lineStart));
                    inTable = false;
                }
                inFence = true;
                fenceChar = line.charAt(0);
                blockStart = lineStart;
            } else {
                boolean isTableLine = !line.isEmpty() && line.charAt(0) == '|';
                if (isTableLine && !inTable) {
                    inTable = true;
                    blockStart = lineStart;
                } else if (!isTableLine && inTable) {
                    blocks.add(new StructSpan(blockStart, lineStart));
                    inTable = false;
                }
            }
            if (!hasNl) {
                break;
            }
            lineStart = lineEnd + 1;
            if (lineStart >= content.length()) {
                break;
            }
        }
        if (inFence || inTable) {
            blocks.add(new StructSpan(blockStart, content.length()));
        }
        for (StructSpan b : blocks) {
            if (offset >= b.start && offset < b.end) {
                return b;
            }
        }
        return null;
    }

    /** 开栏行：trim 后以 ≥3 个连续 {@code '} 反引号或 {@code ~} 波浪线开始 */
    private static boolean isFenceOpenLine(String trimmed) {
        if (trimmed.length() < 3) {
            return false;
        }
        char c = trimmed.charAt(0);
        return (c == '`' && trimmed.startsWith("```")) || (c == '~' && trimmed.startsWith("~~~"));
    }

    /** 闭栏行：trim 后仅由同一种围栏字符组成（≥3 个） */
    private static boolean isFenceCloseLine(String trimmed, char fenceChar) {
        if (trimmed.length() < 3) {
            return false;
        }
        for (int i = 0; i < trimmed.length(); i++) {
            if (trimmed.charAt(i) != fenceChar) {
                return false;
            }
        }
        return true;
    }
}
