package com.ai.konwledgerepo.service.document;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TableExtractorTest {

    // ===== 标准 pipe 表格提取 =====

    @Test
    void standardTable_extractedAsTableBlock() {
        String md = "正文段落。\n\n| 类别 | 颜色 |\n|------|------|\n| a | 红 |\n| b | 绿 |\n\n后续正文。";
        List<TableExtractor.Segment> segs = TableExtractor.extract(md);
        assertEquals(3, segs.size(), "正文段 + 表格段 + 正文段");
        assertTrue(segs.get(0) instanceof TableExtractor.TextBlock);
        assertTrue(segs.get(1) instanceof TableExtractor.TableBlock);
        assertTrue(segs.get(2) instanceof TableExtractor.TextBlock);

        TableExtractor.Table t = ((TableExtractor.TableBlock) segs.get(1)).table();
        assertEquals(List.of("类别", "颜色"), t.header());
        assertEquals(2, t.rows().size());
        assertEquals("a", t.rows().get(0).get(0));
    }

    @Test
    void tableMarkdown_preservesStructure() {
        TableExtractor.Table t = new TableExtractor.Table(null, List.of("类别", "颜色"),
                List.of(List.of("a", "红"), List.of("b", "绿")));
        String md = t.toMarkdown();
        assertTrue(md.startsWith("| 类别 | 颜色 |"), "应以表头开头");
        assertTrue(md.contains("| --- | --- |"), "应含分隔行");
        assertTrue(md.contains("| a | 红 |"), "应含数据行");
    }

    // ===== 合并单元格 forward-fill（用户场景：类别 a/b 跨多种颜色）=====

    @Test
    void mergedCellEmptyCell_forwardFilled() {
        String md = "| 类别 | 颜色 |\n|------|------|\n| a | 红 |\n|   | 黄 |\n|   | 蓝 |\n| b | 绿 |\n|   | 黑 |";
        List<TableExtractor.Segment> segs = TableExtractor.extract(md);
        TableExtractor.Table t = ((TableExtractor.TableBlock) segs.get(0)).table();
        // 空类别列应回填上一行值
        assertEquals(List.of("a", "a", "a", "b", "b"),
                t.rows().stream().map(r -> r.get(0)).toList(), "合并单元格应 forward-fill");
        assertEquals(List.of("红", "黄", "蓝", "绿", "黑"),
                t.rows().stream().map(r -> r.get(1)).toList());
    }

    @Test
    void mergedCell_anyColumnForwardFilled() {
        String md = "| 名称 | 值 |\n|---|---|\n| A | 1 |\n| B |   |\n| C | 3 |";
        List<TableExtractor.Segment> segs = TableExtractor.extract(md);
        TableExtractor.Table t = ((TableExtractor.TableBlock) segs.get(0)).table();
        assertEquals("1", t.rows().get(1).get(1), "值列空 cell 也应回填");
    }

    // ===== 列宽对齐 =====

    @Test
    void raggedRows_paddedToMaxWidth() {
        String md = "| a | b | c |\n|---|---|---|\n| 1 | 2 |\n| 3 | 4 | 5 |";
        List<TableExtractor.Segment> segs = TableExtractor.extract(md);
        TableExtractor.Table t = ((TableExtractor.TableBlock) segs.get(0)).table();
        assertEquals(3, t.header().size());
        assertEquals(3, t.rows().get(0).size(), "缺列行应补齐");
        assertEquals("", t.rows().get(0).get(2));
    }

    // ===== caption 吸附 =====

    @Test
    void captionAboveTable_absorbedIntoTable() {
        String md = "表1-1 设备清单\n\n| 设备 | 数量 |\n|------|------|\n| 路由 | 2 |";
        List<TableExtractor.Segment> segs = TableExtractor.extract(md);
        assertEquals(1, segs.size(), "仅表格段（caption 被吸附）");
        TableExtractor.Table t = ((TableExtractor.TableBlock) segs.get(0)).table();
        assertEquals("表1-1 设备清单", t.caption());
        assertTrue(t.toMarkdown().startsWith("表1-1 设备清单"), "caption 应为表格首行");
    }

    @Test
    void boldCaption_absorbed() {
        String md = "**表 2 参数说明**\n\n| 参数 | 默认值 |\n|---|---|\n| x | 1 |";
        List<TableExtractor.Segment> segs = TableExtractor.extract(md);
        TableExtractor.Table t = ((TableExtractor.TableBlock) segs.get(0)).table();
        assertEquals("**表 2 参数说明**", t.caption());
    }

    @Test
    void textResemblingCaption_notAbsorbedWithoutTable() {
        // 无表格时"表1-1 xxx"应留在正文
        List<TableExtractor.Segment> segs = TableExtractor.extract("表1-1 设备清单\n\n这是正文描述。");
        assertEquals(1, segs.size());
        assertTrue(segs.get(0) instanceof TableExtractor.TextBlock);
    }

    // ===== 误判防护 =====

    @Test
    void listLines_notTreatedAsTable() {
        String md = "- 项目一\n- 项目二\n* 项目三";
        List<TableExtractor.Segment> segs = TableExtractor.extract(md);
        assertEquals(1, segs.size());
        assertTrue(segs.get(0) instanceof TableExtractor.TextBlock, "无分隔行的 pipe 行应留在正文");
    }

    @Test
    void pipeBlockWithoutSeparator_notTable() {
        String md = "| a | b |\n| c | d |";
        List<TableExtractor.Segment> segs = TableExtractor.extract(md);
        assertEquals(1, segs.size());
        assertTrue(segs.get(0) instanceof TableExtractor.TextBlock, "缺分隔行不算表格");
    }

    @Test
    void emptyHeaderCells_fallbackToColumnN() {
        String md = "| 参数 | |\n|---|---|\n| x | 1 |";
        List<TableExtractor.Segment> segs = TableExtractor.extract(md);
        TableExtractor.Table t = ((TableExtractor.TableBlock) segs.get(0)).table();
        assertEquals(List.of("参数", "列2"), t.header());
    }

    @Test
    void tableWithoutHeaderRow_headerFallback() {
        // 表格以分隔行开头（无表头行）→ 列名兜底
        String md = "|---|\n| 值 |";
        List<TableExtractor.Segment> segs = TableExtractor.extract(md);
        TableExtractor.Table t = ((TableExtractor.TableBlock) segs.get(0)).table();
        assertEquals("列1", t.header().get(0));
    }

    @Test
    void tableWithNoDataRows_notProduced() {
        String md = "| 类别 | 颜色 |\n|------|------|";
        List<TableExtractor.Segment> segs = TableExtractor.extract(md);
        assertEquals(1, segs.size());
        assertTrue(segs.get(0) instanceof TableExtractor.TextBlock, "无数据行不产出表格块");
    }

    @Test
    void emptyAndNullText_noSegments() {
        assertTrue(TableExtractor.extract(null).isEmpty());
        assertTrue(TableExtractor.extract("").isEmpty());
        assertTrue(TableExtractor.extract("   \n  ").isEmpty());
    }

    @Test
    void textWithoutTable_singleTextBlock() {
        List<TableExtractor.Segment> segs = TableExtractor.extract("# 标题\n\n正文内容。\n\n- 列表项");
        assertEquals(1, segs.size());
        assertEquals("# 标题\n\n正文内容。\n\n- 列表项", ((TableExtractor.TextBlock) segs.get(0)).text());
        assertFalse(segs.stream().anyMatch(s -> s instanceof TableExtractor.TableBlock));
    }

    @Test
    void multipleTables_extractedInOrder() {
        String md = "表1\n\n| a | b |\n|---|---|\n| 1 | 2 |\n\n中间正文。\n\n| c | d |\n|---|---|\n| 3 | 4 |";
        List<TableExtractor.Segment> segs = TableExtractor.extract(md);
        assertEquals(3, segs.size(), "表1 + 正文 + 表2");
        assertEquals(2, segs.stream().filter(s -> s instanceof TableExtractor.TableBlock).count());
    }
}
