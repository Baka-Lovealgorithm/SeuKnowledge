package com.ai.konwledgerepo.service.document;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Markdown 表格提取器（面向 LlamaParse 输出的 pipe 表格）。
 *
 * <p>职责：把 md 文本切成"正文段 + 表格段"的有序列表，表格段已规整化：
 * <ul>
 *   <li><b>识别</b>：连续 {@code |} 开头的行且含分隔行（{@code |---|}）才确认是表格，避免列表行误判；</li>
 *   <li><b>caption 吸附</b>：表格上方紧邻的 {@code 表1-1 xxx} / {@code **表1 xxx**} 行并入表格作标题行；</li>
 *   <li><b>列宽对齐</b>：所有行按最大列数补齐空 cell；</li>
 *   <li><b>forward-fill</b>：数据行中空 cell 继承上一行同列值——还原 Markdown 无法表达的
 *       "合并单元格"语义（如类别 a 跨多行颜色），保证行级语义完整；</li>
 *   <li><b>表头兜底</b>：空表头 cell 用 {@code 列N} 补全。</li>
 * </ul>
 */
public final class TableExtractor {

    /** 分隔行：| --- | :---: | 等（含 2 个以上 - 或 :） */
    private static final Pattern SEPARATOR_ROW = Pattern.compile("^\\|?\\s*:?-+:?\\s*(?:\\|\\s*:?-+:?\\s*)*\\|?$");

    /** 表格标题行：表1-1 xxx / 表 2 xxx / **表1 xxx**（须带数字，避免"表格"误判） */
    private static final Pattern CAPTION = Pattern.compile("^\\*{0,2}表\\s*\\d+(?:[-.．]\\d+)*[^\\n]*$");

    private TableExtractor() {
    }

    /** 段落类型：正文段（含标题行）或表格段 */
    public sealed interface Segment permits TextBlock, TableBlock {
    }

    /** 正文段：走既有递归分块 */
    public record TextBlock(String text) implements Segment {
    }

    /** 表格段：规整化后的表格（caption + 表头 + 回填后行数据） */
    public record TableBlock(Table table) implements Segment {
    }

    /** 规整化表格模型 */
    public record Table(String caption, List<String> header, List<List<String>> rows) {

        /** 回填后的 pipe 原文（caption 为首行，含表头与分隔行），供分块/展示/检索 */
        public String toMarkdown() {
            StringBuilder sb = new StringBuilder();
            if (caption != null && !caption.isBlank()) {
                sb.append(caption).append('\n');
            }
            sb.append("| ").append(String.join(" | ", header)).append(" |\n");
            sb.append("| ").append(header.stream().map(h -> "---").collect(java.util.stream.Collectors.joining(" | "))).append(" |\n");
            for (List<String> row : rows) {
                sb.append("| ").append(String.join(" | ", row)).append(" |\n");
            }
            return sb.toString().trim();
        }
    }

    /**
     * 提取表格段：返回正文段与表格段的有序列表（保持原文本顺序，供 seq/页码衔接）。
     */
    public static List<Segment> extract(String text) {
        List<Segment> segments = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return segments;
        }
        String[] lines = text.split("\\r?\\n", -1);
        // 正文行累积（保留空行以维持段落结构）；取回 caption 时需按行操作
        List<String> textLines = new ArrayList<>();
        int i = 0;
        while (i < lines.length) {
            String trimmed = lines[i].trim();
            if (trimmed.isEmpty()) {
                textLines.add("");
                i++;
                continue;
            }
            if (!isPipeLine(trimmed)) {
                textLines.add(trimmed);
                i++;
                continue;
            }
            // 收集连续 pipe 行块
            int start = i;
            int end = i;
            while (end < lines.length) {
                String t = lines[end].trim();
                if (t.isEmpty() || !isPipeLine(t)) {
                    break;
                }
                end++;
            }
            // 块内找分隔行确认表格
            int sepIdx = -1;
            for (int k = start; k < end; k++) {
                if (isSeparatorRow(lines[k].trim())) {
                    sepIdx = k;
                    break;
                }
            }
            if (sepIdx >= 0) {
                // caption 吸附：正文累积中最后一个非空行
                String caption = null;
                for (int k = textLines.size() - 1; k >= 0; k--) {
                    String t = textLines.get(k).trim();
                    if (!t.isEmpty()) {
                        if (isCaption(t)) {
                            caption = t;
                            // 从正文累积中移除 caption 行及其后的空行，保持文本连贯
                            while (textLines.size() > k) {
                                textLines.remove(textLines.size() - 1);
                            }
                        }
                        break;
                    }
                }
                Table table = parseTable(caption, lines, start, sepIdx, end);
                if (table != null && !table.rows().isEmpty()) {
                    flushText(textLines, segments);
                    segments.add(new TableBlock(table));
                    i = end;
                    continue;
                }
            }
            // 非表格 pipe 行：按正文累积
            for (int k = start; k < end; k++) {
                textLines.add(lines[k]);
            }
            i = end;
        }
        flushText(textLines, segments);
        return segments;
    }

    /** 解析并规整化表格（列宽对齐 + forward-fill + 表头兜底） */
    private static Table parseTable(String caption, String[] lines, int blockStart, int sepIdx, int blockEnd) {
        // 表头 = 分隔行上一行（须在块内且为 pipe 行）
        List<String> headerCells = null;
        int dataStart = sepIdx + 1;
        if (sepIdx > blockStart && isPipeLine(lines[sepIdx - 1].trim())) {
            headerCells = splitCells(lines[sepIdx - 1].trim());
        }
        List<List<String>> rows = new ArrayList<>();
        for (int k = dataStart; k < blockEnd; k++) {
            String t = lines[k].trim();
            if (t.isEmpty()) {
                continue;
            }
            rows.add(splitCells(t));
        }
        if (rows.isEmpty()) {
            return null; // 无数据行 → 不产出表格块
        }
        int cols = 0;
        if (headerCells != null) {
            cols = Math.max(cols, headerCells.size());
        }
        for (List<String> r : rows) {
            cols = Math.max(cols, r.size());
        }
        if (cols == 0) {
            return null;
        }
        // 表头兜底
        List<String> header = new ArrayList<>(cols);
        for (int c = 0; c < cols; c++) {
            String h = (headerCells != null && c < headerCells.size() && !headerCells.get(c).isBlank())
                    ? headerCells.get(c).trim() : "列" + (c + 1);
            header.add(h);
        }
        // 列宽对齐 + forward-fill（合并单元格语义还原）
        for (int r = 0; r < rows.size(); r++) {
            List<String> row = rows.get(r);
            while (row.size() < cols) {
                row.add("");
            }
            if (r > 0) {
                List<String> prev = rows.get(r - 1);
                for (int c = 0; c < cols; c++) {
                    if (row.get(c).isBlank() && !prev.get(c).isBlank()) {
                        row.set(c, prev.get(c));
                    }
                }
            }
        }
        return new Table(caption, header, rows);
    }

    /** 是否为候选 pipe 行（以 | 开头） */
    private static boolean isPipeLine(String trimmed) {
        return trimmed.startsWith("|") && trimmed.length() > 1;
    }

    /** 是否为分隔行（| --- | / |:---:| 等，仅含 - : | 空格） */
    private static boolean isSeparatorRow(String trimmed) {
        return trimmed.contains("-") && SEPARATOR_ROW.matcher(trimmed).matches();
    }

    /** 是否为表格标题行（表1-1 xxx / **表1 xxx**） */
    private static boolean isCaption(String trimmed) {
        return CAPTION.matcher(trimmed).matches();
    }

    /** pipe 行 → cell 列表（去首尾 |，按 | 拆分并 trim；\| 转义不处理） */
    private static List<String> splitCells(String line) {
        String s = line.trim();
        if (s.startsWith("|")) {
            s = s.substring(1);
        }
        if (s.endsWith("|")) {
            s = s.substring(0, s.length() - 1);
        }
        List<String> cells = new ArrayList<>();
        for (String c : s.split("\\|", -1)) {
            cells.add(c.trim());
        }
        return cells;
    }

    /** 正文行累积 → 正文段（保留空行结构） */
    private static void flushText(List<String> textLines, List<Segment> segments) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (String line : textLines) {
            if (!first) {
                sb.append('\n');
            }
            sb.append(line);
            first = false;
        }
        textLines.clear();
        if (sb.length() > 0 && !sb.toString().isBlank()) {
            segments.add(new TextBlock(sb.toString()));
        }
    }
}
