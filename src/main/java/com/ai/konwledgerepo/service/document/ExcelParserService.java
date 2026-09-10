package com.ai.konwledgerepo.service.document;

import com.ai.konwledgerepo.common.BizException;
import com.ai.konwledgerepo.config.props.SeuDocumentProperties;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.CellValue;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellRangeAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Excel 解析服务（本地 POI，数据不出本地）。
 *
 * <p>流程：{@code WorkbookFactory} 打开 xlsx/xls → 逐 sheet 构造单元格矩阵 →
 * 合并区域展开（纵向合并广播左上角值 = "单个类别跨多个子项"的 forward-fill；
 * 横向合并除左上角外置空并冻结，防止被上一行数据污染）→ 普通空单元格向下继承 →
 * 划分 caption/表头/数据 → 构造 {@link TableExtractor.Table} 复用
 * {@link RecursiveChunkSplitter#tablePieces} 的 A+B 分块（小表原子块、大表行组+表头）。
 *
 * <p>约定：
 * <ul>
 *   <li>开头横跨整行的标题行 → caption（并入 chunk 内容首行），不进表头/数据；</li>
 *   <li>末尾总结行（合计/总分，含横向合并）→ 保留为数据行，不剥离；</li>
 *   <li>公式：用 {@link FormulaEvaluator} 求值（Excel 保存时可能无缓存值），失败留空不阻断；</li>
 *   <li>图片等多媒体：POI 读单元格不涉及 drawing，天然忽略；</li>
 *   <li>chunk title = {@code 文件名(去扩展名) - sheet名}，pageNum = sheet 序号（1 起始）。</li>
 * </ul>
 */
@Service
public class ExcelParserService {

    private static final Logger log = LoggerFactory.getLogger(ExcelParserService.class);

    /** 文件名段最大长度（对齐 Headings.MAX_LEVEL_TEXT，防超长文件名撑爆 title） */
    private static final int MAX_NAME = 80;

    private final int chunkSize;

    public ExcelParserService(SeuDocumentProperties docProps) {
        this.chunkSize = docProps.chunkSize();
    }

    /**
     * 解析 Excel 为分块片段列表（每 sheet 一张表，独立 A+B 分块）。
     *
     * @param path     落盘文件路径（.xlsx / .xls）
     * @param fileName 原始文件名（chunk title 前缀）
     */
    public List<ChunkPiece> parse(Path path, String fileName) {
        List<ChunkPiece> pieces = new ArrayList<>();
        try (Workbook workbook = WorkbookFactory.create(path.toFile())) {
            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            String nameBase = nameBase(fileName);
            int sheetCount = workbook.getNumberOfSheets();
            for (int i = 0; i < sheetCount; i++) {
                Sheet sheet = workbook.getSheetAt(i);
                pieces.addAll(parseSheet(sheet, i + 1, nameBase, evaluator));
            }
            if (pieces.isEmpty()) {
                // 与 PPTX 空文档行为一致：不抛异常，返回空列表（DocumentParseExecutor 标记 SUCCESS、chunkCount=0）
                log.warn("Excel {} 解析完成但无可用内容（sheet 全空或无有效表头）", fileName);
            }
            return pieces;
        } catch (IOException | RuntimeException e) {
            throw new BizException("Excel 解析失败: " + e.getMessage());
        }
    }

    /** 单 sheet → 单元格矩阵 → 规整化 → 表格 A+B 分块 */
    private List<ChunkPiece> parseSheet(Sheet sheet, int sheetNo, String nameBase, FormulaEvaluator evaluator) {
        List<CellRangeAddress> merged = sheet.getMergedRegions();
        int maxRow = -1;
        int maxCol = -1;
        for (Row row : sheet) {
            maxRow = Math.max(maxRow, row.getRowNum());
            maxCol = Math.max(maxCol, row.getLastCellNum() - 1);
        }
        for (CellRangeAddress m : merged) {
            maxRow = Math.max(maxRow, m.getLastRow());
            maxCol = Math.max(maxCol, m.getLastColumn());
        }
        if (maxRow < 0 || maxCol < 0) {
            return List.of(); // 空 sheet
        }
        int rows = maxRow + 1;
        int cols = maxCol + 1;
        String[][] cells = new String[rows][cols];
        boolean[][] frozen = new boolean[rows][cols];
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                cells[r][c] = "";
            }
        }
        // 1) 读单元格文本（公式走求值器）
        for (Row row : sheet) {
            int r = row.getRowNum();
            for (int c = 0; c < cols; c++) {
                Cell cell = row.getCell(c);
                if (cell != null) {
                    cells[r][c] = cellText(cell, evaluator);
                }
            }
        }
        // 2) 表头行识别：必须在合并区域展开之前算，否则纵向广播会把表头值填满下方数据行，
        //    使 findHeaderRow 的「非空列数 > 1」在多个行同时成立，表头位置被误判（进而让步骤 3 跳错继承行）。
        //    基于原始单元格文本识别才是稳定判据。
        int headerRow = findHeaderRow(cells, rows, cols);
        // 3) 合并区域展开：纵向广播左上角值；横向/双向除左上角置空并冻结（禁止被继承污染）
        for (CellRangeAddress m : merged) {
            String value = cells[m.getFirstRow()][m.getFirstColumn()];
            if (m.getFirstColumn() == m.getLastColumn() && m.getFirstRow() < m.getLastRow()) {
                for (int r = m.getFirstRow() + 1; r <= m.getLastRow(); r++) {
                    cells[r][m.getFirstColumn()] = value;
                }
            } else {
                for (int r = m.getFirstRow(); r <= m.getLastRow(); r++) {
                    for (int c = m.getFirstColumn(); c <= m.getLastColumn(); c++) {
                        if (r == m.getFirstRow() && c == m.getFirstColumn()) {
                            continue;
                        }
                        cells[r][c] = "";
                        frozen[r][c] = true;
                    }
                }
            }
        }
        // 4) 普通空单元格向下继承（跳过冻结格；表头行不作为继承源，避免表头文本污染数据区）
        for (int r = 1; r < rows; r++) {
            if (r - 1 == headerRow) {
                continue; // 第一数据行不继承表头行
            }
            for (int c = 0; c < cols; c++) {
                if (!frozen[r][c] && cells[r][c].isEmpty() && !cells[r - 1][c].isEmpty()) {
                    cells[r][c] = cells[r - 1][c];
                }
            }
        }
        // 5) 划分 caption（首个横跨标题行）/ 表头（其后首个非空行）/ 数据（其余非空行）
        String caption = null;
        List<String> header = null;
        List<List<String>> dataRows = new ArrayList<>();
        for (int r = 0; r < rows; r++) {
            List<String> row = rowList(cells, r, cols);
            if (isBlankRow(row)) {
                continue;
            }
            int nonBlank = countNonBlank(row);
            if (header == null) {
                if (nonBlank <= 1) {
                    caption = row.stream().filter(s -> !s.isBlank()).findFirst().orElse("");
                    continue;
                }
                header = normalizeHeader(row);
                continue;
            }
            dataRows.add(row);
        }
        if (header == null || dataRows.isEmpty()) {
            return List.of(); // 无有效表头或无数行 → 不产出表格块
        }
        // 6) 构造表格并复用 A+B 分块（title = 文件名 - sheet名；pageNum = sheet 序号）
        TableExtractor.Table table = new TableExtractor.Table(caption, header, dataRows);
        String title = (nameBase + " - " + sheet.getSheetName());
        if (title.length() > Headings.MAX_LEVEL_TEXT) {
            title = title.substring(0, Headings.MAX_LEVEL_TEXT);
        }
        List<Headings.StackEntry> stack = List.of(new Headings.StackEntry(1, title));
        return RecursiveChunkSplitter.tablePieces(table, chunkSize, stack, sheetNo);
    }

    /** 单元格 → 文本（公式用求值器；数值整数去 .0，日期转 yyyy-MM-dd；失败留空不阻断） */
    private static String cellText(Cell cell, FormulaEvaluator evaluator) {
        CellType type = cell.getCellType();
        if (type == CellType.FORMULA) {
            if (evaluator == null) {
                return "";
            }
            try {
                CellValue v = evaluator.evaluate(cell);
                return switch (v.getCellType()) {
                    case STRING -> v.getStringValue().trim();
                    case NUMERIC -> numberText(v.getNumberValue(), cell);
                    case BOOLEAN -> String.valueOf(v.getBooleanValue());
                    default -> "";
                };
            } catch (Exception e) {
                return "";
            }
        }
        return switch (type) {
            case STRING -> cell.getStringCellValue().trim();
            case NUMERIC -> numberText(cell.getNumericCellValue(), cell);
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            default -> "";
        };
    }

    /** 数值 → 文本：整数去 .0；日期格式按样式转 yyyy-MM-dd */
    private static String numberText(double d, Cell cell) {
        if (DateUtil.isCellDateFormatted(cell)) {
            try {
                return cell.getLocalDateTimeCellValue().toLocalDate().toString();
            } catch (Exception ignored) {
                // 日期格式解析失败按普通数值处理
            }
        }
        if (d == Math.rint(d) && Math.abs(d) < 1e15) {
            return String.valueOf((long) d);
        }
        return String.valueOf(d);
    }

    /** 表头行索引：第一个非空且非横跨（非空 cell > 1）的行；无则 -1（用于禁止表头向数据继承） */
    private static int findHeaderRow(String[][] cells, int rows, int cols) {
        for (int r = 0; r < rows; r++) {
            if (isBlankRow(rowList(cells, r, cols))) {
                continue;
            }
            if (countNonBlank(rowList(cells, r, cols)) > 1) {
                return r;
            }
        }
        return -1;
    }

    /** 表头兜底：空 cell 补 列N（与 TableExtractor.parseTable 行为一致） */
    private static List<String> normalizeHeader(List<String> row) {
        List<String> h = new ArrayList<>(row.size());
        for (int c = 0; c < row.size(); c++) {
            String v = row.get(c);
            h.add(v == null || v.isBlank() ? "列" + (c + 1) : v.trim());
        }
        return h;
    }

    private static List<String> rowList(String[][] cells, int r, int cols) {
        List<String> list = new ArrayList<>(cols);
        for (int c = 0; c < cols; c++) {
            list.add(cells[r][c]);
        }
        return list;
    }

    private static boolean isBlankRow(List<String> row) {
        for (String s : row) {
            if (s != null && !s.isBlank()) {
                return false;
            }
        }
        return true;
    }

    private static int countNonBlank(List<String> row) {
        int n = 0;
        for (String s : row) {
            if (s != null && !s.isBlank()) {
                n++;
            }
        }
        return n;
    }

    /** 文件名去扩展名，超长截断 */
    private static String nameBase(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "Excel";
        }
        String base = fileName;
        int dot = fileName.lastIndexOf('.');
        if (dot > 0) {
            base = fileName.substring(0, dot);
        }
        return base.length() > MAX_NAME ? base.substring(0, MAX_NAME) : base;
    }
}
