package com.ai.konwledgerepo.service.document;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 递归分词器（仿 LlamaIndex RecursiveCharacterTextSplitter，面向 LlamaParse 输出的 Markdown）。
 *
 * <p>策略：按分隔符优先级逐层切分——Markdown 二级标题 → 三级标题 → … → 一级标题 → 空行（段落）
 * → 换行 → 中文/英文句子标点 → 空格 → 字符兜底；片段仍超限时用更低优先级分隔符递归切分。
 * 输出块自动携带最近出现的 Markdown 标题（ChunkPiece.title，供引用展示）。
 *
 * <p>参数沿用既有分块配置（chunk-size / chunk-overlap）；跨页 carry 语义与 {@link ChunkSplitter#splitWithCarry}
 * 一致：页尾重叠文本并入下一页首个真实片段，下一页以标题行开头时丢弃 carry（章节边界无需衔接）。
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

    private static final Pattern HEADING_LINE = Pattern.compile("^#{1,6}\\s+\\S.*$", Pattern.MULTILINE);
    private static final Pattern FIRST_LINE = Pattern.compile("^[\\s\\uFEFF]*([^\\n]+)");

    private RecursiveChunkSplitter() {
    }

    /**
     * 跨页分块结果：块列表 + 页尾重叠文本（供下一页作为 carry 延续）+ 页内最后标题（供下一页继承）。
     */
    public record SplitResult(List<ChunkPiece> pieces, String carryOut, String lastTitle) {
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
     * 本页以标题行开头时丢弃 carryIn。inheritTitle 为上一页末标题，供本页无标题块继承。
     * 返回本页最后一个块的尾部重叠文本（carryOut）与页内最后标题（lastTitle）。
     */
    public static SplitResult splitWithCarry(String text, int pageNum, int chunkSize, int overlap,
                                             String carryIn, String inheritTitle) {
        if (text == null || text.isBlank()) {
            // 空页：carry 透传，不打断上下文流
            return new SplitResult(new ArrayList<>(), carryIn == null ? "" : carryIn, inheritTitle);
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

        // 1) 递归切分为不超过 max 的片段（保留换行结构，尾部 trim）
        List<String> segs = new ArrayList<>();
        splitRecursive(carry.isEmpty() ? text : carry + "\n" + text, SEPARATORS, 0, max, segs);

        // 2) 逐片段进窗口：超限输出块并保留 overlap 前缀；标题跟踪（块显示首个标题，块间继承末尾标题）
        List<ChunkPiece> pieces = new ArrayList<>();
        StringBuilder window = new StringBuilder();
        String prevTitle = inheritTitle;
        for (String seg : segs) {
            if (window.length() > 0 && window.length() + seg.length() > max) {
                prevTitle = flush(pieces, window, ov, prevTitle, pageNum);
            }
            window.append(seg).append('\n');
        }
        prevTitle = flush(pieces, window, ov, prevTitle, pageNum);
        return new SplitResult(pieces, window.toString().trim(), prevTitle);
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
     * 块标题：取块内第一个标题用于展示；块内最后一个标题用于后续块继承（无标题块继承前置标题）。
     *
     * @return 供后续块继承的标题
     */
    private static String flush(List<ChunkPiece> result, StringBuilder window, int overlap,
                                String prevTitle, int pageNum) {
        if (window.length() == 0) {
            return prevTitle;
        }
        String block = window.toString().trim();
        String blockTitle = null;
        String inheritTitle = prevTitle;
        if (!block.isEmpty()) {
            Matcher m = HEADING_LINE.matcher(block);
            boolean first = true;
            while (m.find()) {
                String t = m.group().replaceFirst("^#{1,6}\\s+", "").trim();
                if (first) {
                    blockTitle = t;
                    first = false;
                }
                inheritTitle = t;
            }
            if (blockTitle == null) {
                blockTitle = prevTitle;
            }
            result.add(new ChunkPiece(block, pageNum, blockTitle));
        }
        String tail = "";
        if (overlap > 0 && window.length() > overlap) {
            tail = window.substring(window.length() - overlap);
        }
        window.setLength(0);
        if (!tail.isEmpty()) {
            window.append(tail).append('\n');
        }
        return inheritTitle;
    }

    /** 文本首个非空行是否为 Markdown 标题 */
    private static boolean startsWithHeading(String text) {
        Matcher m = FIRST_LINE.matcher(text);
        if (!m.find()) {
            return false;
        }
        String first = m.group(1).trim();
        return first.startsWith("#") && first.matches("^#{1,6}\\s+\\S.*$");
    }
}
