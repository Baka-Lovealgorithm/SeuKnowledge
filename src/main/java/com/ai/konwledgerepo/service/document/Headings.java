package com.ai.konwledgerepo.service.document;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 标题层级工具：识别文档标题行并维护"祖先链"路径（section_path）。
 *
 * <p>标题类型与层级推断规则：
 * <ul>
 *   <li>Markdown 标题（{@code #} ~ {@code ######}）：层级 = {@code #} 数量（1-6）</li>
 *   <li>中文章节/条款（第X章/篇/部分/条…）：层级 = 1</li>
 *   <li>中文序号（一、二、三…）：层级 = 2</li>
 *   <li>数字序号（1. / 1.1 / 1.1.1…）：层级 = 数字段数</li>
 * </ul>
 * 均要求标题不以句号/感叹号/分号等结尾（避免正文整句误判）。
 *
 * <p>祖先链由标题栈表达：{@link #apply} 遇到同层/更高层标题时截断旧分支（同级替换、跨级重建），
 * {@link #path} 将栈拼接为 {@code 根 > 中 > 叶} 形式的路径字符串
 * （总长超限时优先保留最近层级，供 DB/ES 字段安全存储）。
 */
public final class Headings {

    /** 路径分隔符 */
    public static final String SEPARATOR = " > ";

    /** 路径总长上限（chars；MySQL chunk.title 为 255，留余量） */
    public static final int MAX_TITLE = 200;

    /** 单级标题文本长度上限 */
    public static final int MAX_LEVEL_TEXT = 100;

    /** 标题栈深度上限（对齐 Markdown 1-6 级标题） */
    public static final int MAX_STACK = 6;

    /** Markdown 标题（# ~ ######），标题行不以句号等标点结尾 */
    private static final Pattern MD_HEADING = Pattern.compile("^#{1,6}\\s+\\S[^。！？!?；;]*$");

    /** 中文章节/条款：第一章、第2篇、第3部分、第四条 … */
    private static final Pattern CN_CHAPTER = Pattern.compile("^第[0-9一二三四五六七八九十百千]+[章节篇部分条款][^。！？!?；;]*$");

    /** 中文序号：一、二、三、…（可接 、 . ． 分隔） */
    private static final Pattern CN_ITEM = Pattern.compile("^[一二三四五六七八九十]+[、.．][^。！？!?；;]*$");

    /** 数字序号：1. / 1.1 / 1.1.1 …（层级 = 数字段数） */
    private static final Pattern NUM_ITEM = Pattern.compile("^\\d{1,3}(?:[.．、]\\d{1,3})*[.．、]?\\s*\\S[^。！？!?；;]*$");

    /** 数字序号前缀（行首数字段序列，用于统计段数） */
    private static final Pattern NUM_PREFIX = Pattern.compile("^\\d{1,3}(?:[.．、]\\d{1,3})*[.．、]?");

    private static final Pattern DIGIT_SEGMENT = Pattern.compile("\\d{1,3}");

    private Headings() {
    }

    /** 标题：层级（1-6）+ 干净文本（Markdown 剥 # 前缀，其余保留原行） */
    public record Heading(int level, String text) {
    }

    /**
     * 识别标题行并推断层级；非标题返回 null。
     *
     * @param line 单行文本（可含首尾空白）
     */
    public static Heading parse(String line) {
        if (line == null) {
            return null;
        }
        String trimmed = line.trim();
        Matcher md = MD_HEADING.matcher(trimmed);
        if (md.matches()) {
            int level = 0;
            while (level < trimmed.length() && trimmed.charAt(level) == '#') {
                level++;
            }
            return new Heading(level, trimmed.substring(level).trim());
        }
        if (CN_CHAPTER.matcher(trimmed).matches()) {
            return new Heading(1, trimmed);
        }
        if (CN_ITEM.matcher(trimmed).matches()) {
            return new Heading(2, trimmed);
        }
        if (NUM_ITEM.matcher(trimmed).matches()) {
            Matcher pm = NUM_PREFIX.matcher(trimmed);
            int segments = 0;
            if (pm.find()) {
                Matcher dm = DIGIT_SEGMENT.matcher(pm.group());
                while (dm.find()) {
                    segments++;
                }
            }
            return new Heading(Math.min(Math.max(segments, 1), MAX_STACK), trimmed);
        }
        return null;
    }

    /**
     * 应用标题到栈：截断到 {@code level-1}（同层/更高层标题替换旧分支），压入标题文本（单级截断）。
     * 原栈不变，返回新栈。
     *
     * @param stack 当前祖先栈（可为 null/空）
     * @param h     新标题
     */
    public static List<String> apply(List<String> stack, Heading h) {
        List<String> result = new ArrayList<>();
        if (stack != null) {
            int keep = Math.min(Math.max(h.level() - 1, 0), MAX_STACK - 1);
            for (int i = 0; i < Math.min(keep, stack.size()); i++) {
                result.add(stack.get(i));
            }
        }
        String text = h.text() == null ? "" : h.text().trim();
        result.add(text.length() > MAX_LEVEL_TEXT ? text.substring(0, MAX_LEVEL_TEXT) : text);
        return result;
    }

    /**
     * 栈 → 路径字符串（{@code 根 > 中 > 叶}）；空栈返回 ""。
     * 总长超过 {@link #MAX_TITLE} 时从最老级（栈头）开始丢弃，最近标题优先保留。
     */
    public static String path(List<String> stack) {
        if (stack == null || stack.isEmpty()) {
            return "";
        }
        String joined = String.join(SEPARATOR, stack);
        if (joined.length() <= MAX_TITLE) {
            return joined;
        }
        for (int i = 1; i < stack.size(); i++) {
            joined = String.join(SEPARATOR, stack.subList(i, stack.size()));
            if (joined.length() <= MAX_TITLE) {
                return joined;
            }
        }
        return joined; // 单级 ≤ MAX_LEVEL_TEXT，必 ≤ MAX_TITLE
    }
}
