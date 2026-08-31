package com.ai.konwledgerepo.service.document;

import com.ai.konwledgerepo.config.props.SeuDocumentProperties;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileOutputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Excel 解析测试（真实素材 + 程序生成边界）。
 * <p>
 * 真实素材 excelTest/姓名XXX-2024级综合测评问卷 (1).xlsx 覆盖：
 * 开头横行标题（A1:H1 合并）、8 列表头、大量纵向合并（A3:A37 等类别跨行）、
 * 横向合并总结行（A82:E82 + F82/G82 SUM 公式）、公式无缓存值（B3/B38/B66）、8 张图片（应忽略）。
 */
class ExcelParserServiceTest {

    private static final String TEST_FILE = "姓名XXX-2024级综合测评问卷 (1).xlsx";

    @TempDir
    Path tempDir;

    private static ExcelParserService parser() {
        SeuDocumentProperties props = new SeuDocumentProperties(true, true, 50, 100, 3, 800, 120,
                new SeuDocumentProperties.LlamaParse(false, "", "https://api.cloud.llamaindex.ai",
                        "cost_effective", "latest", "ch_sim", 5, 900, "", true, false, null), SeuDocumentProperties.Clean.defaults());
        return new ExcelParserService(props);
    }

    /** 真实素材路径（mvn test 工作目录 = 项目根） */
    private static Path testFile() {
        return Path.of("excelTest", TEST_FILE);
    }

    private static String joinAll(List<ChunkPiece> pieces) {
        return pieces.stream().map(ChunkPiece::content).collect(Collectors.joining("\n"));
    }

    @Test
    void realFile_splitIntoRowGroupsWithCaptionHeaderAndTitle() throws Exception {
        List<ChunkPiece> pieces = parser().parse(testFile(), TEST_FILE);

        assertFalse(pieces.isEmpty(), "应产出分块");
        assertTrue(pieces.size() >= 2, "82 行大表应切多个行组，实际 " + pieces.size());
        String title = "姓名XXX-2024级综合测评问卷 (1) - sheet";
        assertTrue(pieces.stream().allMatch(c -> title.equals(c.title())),
                "chunk title 应为 文件名(去扩展名) - sheet名");
        assertTrue(pieces.stream().allMatch(c -> c.pageNum() == 1), "单 sheet 文件 pageNum = 1");
        assertTrue(pieces.stream().allMatch(c -> c.content().contains("| 模块名称 |")),
                "每个行组都应带表头");
        assertTrue(pieces.stream().allMatch(c -> c.content().contains("2024级综合测评问卷记录（姓名XXX）")),
                "表格标题行（caption）应随每个行组输出");
    }

    @Test
    void verticalMergedCategory_forwardFilledToAllRows() throws Exception {
        List<ChunkPiece> pieces = parser().parse(testFile(), TEST_FILE);
        String all = joinAll(pieces);

        // A3:A37 纵向合并（35 行）："德育评价"应广播到全部 35 行
        long count = countOccurrences(all, "| 德育评价 |");
        assertEquals(35, count, "A3:A37 合并区域应广播 35 行，实际 " + count);
    }

    @Test
    void headerRow_notInheritedIntoSparseColumns() throws Exception {
        List<ChunkPiece> pieces = parser().parse(testFile(), TEST_FILE);
        String all = joinAll(pieces);

        // 原表 G 列（班级评分）整列为空、H 列部分行空：
        // 表头文本不得向下继承污染数据区（修复前 G 列全变成"班级评分"）
        assertEquals(pieces.size(), countOccurrences(all, "| 班级评分 |"),
                "班级评分 应只出现在每 chunk 的表头行，不得污染数据区");
    }

    @Test
    void horizontalMergedSummaryRow_keptBlankAndFormulasEvaluated() throws Exception {
        List<ChunkPiece> pieces = parser().parse(testFile(), TEST_FILE);
        String all = joinAll(pieces);

        // 总结行 A82:E82 横向合并：| 总分 | (空) | (空) | (空) | (空) | F82 | G82 | (空) |
        // B-E 列必须保持空（不被上一行数据污染），F/G 列 SUM 公式应有求值结果
        assertTrue(Pattern.compile("(?m)^\\| 总分 \\|(?:  \\|){4} \\S+ \\| \\S+ \\|").matcher(all).find(),
                "总结行应保留：A=总分、B-E 空、F/G 公式有值");
    }

    @Test
    void formulaCells_evaluatedToValues() throws Exception {
        List<ChunkPiece> pieces = parser().parse(testFile(), TEST_FILE);
        String all = joinAll(pieces);

        // B 列"总分"是公式（=E3+E6+...，无缓存值）：求值后应非空
        assertTrue(Pattern.compile("(?m)^\\| 德育评价 \\| \\S+ \\|").matcher(all).find(),
                "B 列公式应求值出非空结果");
    }

    @Test
    void generatedSimpleSheet_noCaption_headerFirstRow_oneAtomicChunk() throws Exception {
        Path file = tempDir.resolve("simple.xlsx");
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet s = wb.createSheet("S1");
            Row h = s.createRow(0);
            h.createCell(0).setCellValue("A");
            h.createCell(1).setCellValue("B");
            Row r1 = s.createRow(1);
            r1.createCell(0).setCellValue("1");
            r1.createCell(1).setCellValue("2");
            try (FileOutputStream out = new FileOutputStream(file.toFile())) {
                wb.write(out);
            }
        }

        List<ChunkPiece> pieces = parser().parse(file, "simple.xlsx");

        assertEquals(1, pieces.size(), "小表应整表原子块");
        assertTrue(pieces.get(0).content().startsWith("| A | B |"), "表头应为首行（无标题行时不设 caption）");
        assertEquals("simple - S1", pieces.get(0).title());
        assertTrue(pieces.get(0).content().contains("| 1 | 2 |"));
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }
}
