package com.ai.konwledgerepo.graph;

import java.util.ArrayList;
import java.util.List;

/**
 * 引用 snippet 截断工具：为前端 refs 卡片生成 chunk 原文片段。
 * <p>
 * 表格感知：截断点落在 markdown 表格块（连续以 {@code |} 开头的行序列）内时，
 * 整块保留（保证前端按 GFM 渲染成完整表格，而非被拦腰切断的残缺行）。
 * 非表格内容按普通字符截断（超出加省略号）。
 * <p>
 * 普通截断上限 {@value #MAX} 字符；表格块整块保留后最大约等于 chunk 上限（默认 800 字），
 * 前端可展开全文，故 snippet 略超上限可接受。
 */
public final class RefSnippet {

    /** snippet 截断上限（字符） */
    public static final int MAX = 300;

    private RefSnippet() {
    }

    /**
     * 表格感知截断：content 为 null 返回空串；长度 ≤ {@link #MAX} 原样返回；
     * 截断点落在表格块内 → 返回该块完整文本；否则普通截断 + 省略号。
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
        TableSpan hit = findTableSpanAt(content, max);
        if (hit != null) {
            return content.substring(hit.start, hit.end);
        }
        return content.substring(0, max) + "…";
    }

    /** 表格块区间 [start, end)，start/end 均为行边界偏移（end 含行尾换行，便于 trim 后干净展示） */
    private record TableSpan(int start, int end) {
    }

    /**
     * 查找覆盖第 offset 个字符（0 起，即截断点：substring(0, offset) 被保留）的 markdown 表格块。
     * 表格块定义：连续且 trim 后以 {@code |} 开头的行序列（含表头/分隔行/数据行）。
     * 返回 null 表示 offset 不在任何表格块内。
     */
    private static TableSpan findTableSpanAt(String content, int offset) {
        List<TableSpan> blocks = new ArrayList<>();
        int lineStart = 0;
        boolean inTable = false;
        int blockStart = 0;
        while (true) {
            int lineEnd = content.indexOf('\n', lineStart);
            boolean hasNl = lineEnd >= 0;
            if (lineEnd < 0) {
                lineEnd = content.length();
            }
            String line = content.substring(lineStart, lineEnd).trim();
            boolean isTableLine = !line.isEmpty() && line.charAt(0) == '|';
            if (isTableLine && !inTable) {
                inTable = true;
                blockStart = lineStart;
            } else if (!isTableLine && inTable) {
                blocks.add(new TableSpan(blockStart, lineStart));
                inTable = false;
            }
            if (!hasNl) {
                break;
            }
            lineStart = lineEnd + 1;
            if (lineStart >= content.length()) {
                break;
            }
        }
        if (inTable) {
            blocks.add(new TableSpan(blockStart, content.length()));
        }
        for (TableSpan b : blocks) {
            if (offset >= b.start && offset < b.end) {
                return b;
            }
        }
        return null;
    }
}
