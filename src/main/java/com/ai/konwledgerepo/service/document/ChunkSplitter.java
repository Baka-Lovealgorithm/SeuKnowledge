package com.ai.konwledgerepo.service.document;

import java.util.ArrayList;
import java.util.List;

/**
 * 文本分块器：按段落聚合、标题感知（Markdown 标题/中文章节，规则见 {@link Headings}）强制开新块，
 * 块 title 携带标题祖先链路径（如 {@code 第一章 > 1.1 背景}），
 * 超长段落按句子切分，支持块间 overlap（上下文衔接）。
 *
 * 跨页支持：splitWithCarry 把上一页尾部文本（carry）并入下一页首个真实内容片段，
 * 使页边界同样产生重叠；下一页以标题行开头时丢弃 carry（章节边界无需重叠）。
 */
public final class ChunkSplitter {

    /** 默认块长上限（字符） */
    public static final int DEFAULT_MAX = 800;

    /** 默认块间重叠（字符） */
    public static final int DEFAULT_OVERLAP = 0;

    private ChunkSplitter() {
    }

    /**
     * 一次（跨页）分块的结果：块列表 + 页尾重叠文本（供下一页作为 carry 延续）。
     *
     * @param pieces   本页产出的块
     * @param carryOut 本页最后一个块的尾部重叠（≤ overlap 字符，可为空串），
     *                 作为下一页首个片段的前缀以衔接页边界
     */
    public record SplitResult(List<ChunkPiece> pieces, String carryOut) {
    }

    public static List<ChunkPiece> split(String text, int pageNum) {
        return split(text, pageNum, DEFAULT_MAX, DEFAULT_OVERLAP);
    }

    /**
     * 分块：逐行扫描——标题行（Markdown 标题/中文章节）强制开新块；
     * 内容按空行聚合成段落（段内换行保留），超长段落按句切分；
     * 窗口超限时输出块并保留尾部 overlap 字符作为下一块前缀（上下文衔接）。
     *
     * @param chunkSize 块长上限（≤0 用默认 800）
     * @param overlap   块间重叠字符数（自动限制不超过 chunkSize/2）
     */
    public static List<ChunkPiece> split(String text, int pageNum, int chunkSize, int overlap) {
        return splitWithCarry(text, pageNum, chunkSize, overlap, null).pieces();
    }

    /**
     * 跨页分块：carryIn 为上一页尾部的重叠文本，会并入本页首个真实内容片段（作为前缀），
     * 使页边界产生上下文重叠；本页以标题行开头时丢弃 carryIn（章节边界无需重叠）。
     * 返回本页最后一个块的尾部重叠文本（carryOut），供下一页继续传递。
     */
    public static SplitResult splitWithCarry(String text, int pageNum, int chunkSize, int overlap, String carryIn) {
        List<ChunkPiece> result = new ArrayList<>();
        if (text == null || text.isBlank()) {
            // 空页：无内容可分，carry 透传给下一页（不打断上下文流）
            return new SplitResult(result, carryIn == null ? "" : carryIn);
        }
        // 剥离 UTF-8 BOM（常见于 Windows 保存的 txt/md，会影响首行标题识别）
        if (text.charAt(0) == '\uFEFF') {
            text = text.substring(1);
        }
        int max = chunkSize <= 0 ? DEFAULT_MAX : chunkSize;
        int ov = Math.max(0, Math.min(overlap, max / 2));
        // 标题祖先栈（section_path）：块 title 为栈的完整路径（见 Headings）
        List<String> titleStack = new ArrayList<>();
        String currentTitle = null;
        StringBuilder window = new StringBuilder();
        StringBuilder para = new StringBuilder();
        // 跨页重叠载体：并入本页首个真实片段后清空；本页以标题开头时清空丢弃
        StringBuilder carryBuf = new StringBuilder(carryIn == null ? "" : carryIn.trim());

        for (String line : text.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                // 空行：当前段落结束，提交进窗口
                submitParagraph(para, window, max, result, ov, currentTitle, pageNum, carryBuf);
                continue;
            }
            Headings.Heading heading = Headings.parse(trimmed);
            if (heading != null) {
                // 标题行：先提交未完段落，再强制开新块（标题行并入新块开头）
                submitParagraph(para, window, max, result, ov, currentTitle, pageNum, carryBuf);
                if (carryBuf.length() > 0) {
                    // 本页尚无真实内容即以标题开头：丢弃跨页重叠（章节边界无需衔接）
                    carryBuf.setLength(0);
                }
                flush(result, window, max, ov, currentTitle, pageNum);
                titleStack = Headings.apply(titleStack, heading);
                currentTitle = Headings.path(titleStack);
                appendSegment(window, trimmed, max, result, ov, currentTitle, pageNum, carryBuf);
                continue;
            }
            para.append(trimmed).append('\n');
        }
        submitParagraph(para, window, max, result, ov, currentTitle, pageNum, carryBuf);
        flush(result, window, max, ov, currentTitle, pageNum);
        return new SplitResult(result, window.toString().trim());
    }

    /** 提交当前段落（超长按句切）并清空缓冲 */
    private static void submitParagraph(StringBuilder para, StringBuilder window, int max,
                                        List<ChunkPiece> result, int overlap, String title, int pageNum,
                                        StringBuilder carryBuf) {
        if (para.length() == 0) {
            return;
        }
        appendSegments(window, para.toString().trim(), max, result, overlap, title, pageNum, carryBuf);
        para.setLength(0);
    }

    /** 追加一个段落：超长先按句子切成 ≤ max 的片段，再逐个进窗口 */
    private static void appendSegments(StringBuilder window, String para, int max,
                                       List<ChunkPiece> result, int overlap, String title, int pageNum,
                                       StringBuilder carryBuf) {
        if (para.length() > max) {
            for (String seg : splitLongParagraph(para, max)) {
                appendSegment(window, seg, max, result, overlap, title, pageNum, carryBuf);
            }
        } else {
            appendSegment(window, para, max, result, overlap, title, pageNum, carryBuf);
        }
    }

    /** 追加一个片段；窗口将超限时先输出当前窗口（保留 overlap 前缀） */
    private static void appendSegment(StringBuilder window, String seg, int max,
                                      List<ChunkPiece> result, int overlap, String title, int pageNum,
                                      StringBuilder carryBuf) {
        // 跨页重叠：并入本页首个真实片段（作为前缀），仅消费一次
        if (carryBuf.length() > 0) {
            seg = carryBuf.toString() + "\n" + seg;
            carryBuf.setLength(0);
        }
        if (window.length() + seg.length() > max && window.length() > 0) {
            flush(result, window, max, overlap, title, pageNum);
        }
        window.append(seg).append('\n');
        // 硬约束窗口 ≤ max（overlap 前缀 + 超长片段时丢弃最旧部分，保留最近上下文）
        if (window.length() > max) {
            window.delete(0, window.length() - max);
        }
    }

    /** 输出当前窗口为一块，并保留尾部 overlap 字符作为下一块前缀 */
    private static void flush(List<ChunkPiece> result, StringBuilder window, int max, int overlap,
                              String title, int pageNum) {
        if (window.length() == 0) {
            return;
        }
        String block = window.toString().trim();
        if (!block.isEmpty()) {
            result.add(new ChunkPiece(block, pageNum, title));
        }
        String tail = "";
        if (overlap > 0 && window.length() > overlap) {
            tail = window.substring(window.length() - overlap);
        }
        window.setLength(0);
        if (!tail.isEmpty()) {
            window.append(tail).append('\n');
        }
    }

    /**
     * 超长段落按句子（中文/英文句号等）切割为不超过上限的片段 */
    private static List<String> splitLongParagraph(String para, int max) {
        List<String> segs = new ArrayList<>();
        String[] sentences = para.split("(?<=[。！？!?；;])");
        StringBuilder buf = new StringBuilder();
        for (String s : sentences) {
            if (buf.length() + s.length() > max && buf.length() > 0) {
                segs.add(buf.toString().trim());
                buf.setLength(0);
            }
            if (s.length() > max) {
                for (int i = 0; i < s.length(); i += max) {
                    segs.add(s.substring(i, Math.min(i + max, s.length())).trim());
                }
            } else {
                buf.append(s);
            }
        }
        if (buf.length() > 0) {
            segs.add(buf.toString().trim());
        }
        return segs;
    }
}
