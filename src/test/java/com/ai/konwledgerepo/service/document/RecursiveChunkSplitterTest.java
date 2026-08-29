package com.ai.konwledgerepo.service.document;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecursiveChunkSplitterTest {

    @Test
    void blockWithNestedHeadings_titleIsFullPath() {
        String md = "# 第一章\n\n正文甲。\n\n## 1.1 背景\n\n正文乙。";
        List<ChunkPiece> pieces = RecursiveChunkSplitter.split(md, 1, 800, 0);
        assertEquals(1, pieces.size());
        assertEquals("第一章 > 1.1 背景", pieces.get(0).title());
    }

    @Test
    void blockWithoutHeading_inheritsPreviousTitlePath() {
        // 长内容 + 小块长迫使多块；1.1 之后的正文块无标题 → 继承前置标题栈路径
        String body = "正文内容用于填充窗口长度以便切出多个分块。\n\n";
        String md = "# 第一章\n\n" + body.repeat(8)
                + "## 1.1 背景\n\n" + body.repeat(8)
                + "正文丙内容。";
        List<ChunkPiece> pieces = RecursiveChunkSplitter.split(md, 1, 100, 0);
        assertTrue(pieces.size() >= 3, "小块长应切出多块");
        assertTrue(pieces.stream().allMatch(c -> c.title() != null && !c.title().isBlank()),
                "所有块都应携带标题路径（继承或自身）");
        assertEquals("第一章 > 1.1 背景", pieces.get(pieces.size() - 1).title(),
                "末尾正文块应继承 1.1 的完整路径");
    }

    @Test
    void crossPage_inheritsStackFromPreviousPage() {
        RecursiveChunkSplitter.SplitResult sr1 = RecursiveChunkSplitter.splitWithCarry(
                "# 第一章\n\n正文甲。", 1, 800, 0, null, null);
        assertEquals(List.of(new Headings.StackEntry(1, "第一章")), sr1.lastStack(), "第一页末标题栈");

        RecursiveChunkSplitter.SplitResult sr2 = RecursiveChunkSplitter.splitWithCarry(
                "## 1.1 背景\n\n正文乙。", 2, 800, 0, "", sr1.lastStack());
        assertEquals(1, sr2.pieces().size());
        assertEquals("第一章 > 1.1 背景", sr2.pieces().get(0).title(), "下页应继承祖先链");
    }

    @Test
    void crossPage_blankPage_passesStackThrough() {
        RecursiveChunkSplitter.SplitResult sr1 = RecursiveChunkSplitter.splitWithCarry(
                "# 第一章\n\n正文。", 1, 800, 0, null, null);
        RecursiveChunkSplitter.SplitResult blank = RecursiveChunkSplitter.splitWithCarry(
                "   \n  ", 2, 800, 0, "", sr1.lastStack());
        assertTrue(blank.pieces().isEmpty());
        assertEquals(List.of(new Headings.StackEntry(1, "第一章")), blank.lastStack(), "空页应透传标题栈");
    }

    @Test
    void split_noInherit_stackFromScratch() {
        List<ChunkPiece> pieces = RecursiveChunkSplitter.split("## 1.1 背景\n\n正文。", 1);
        assertEquals(1, pieces.size());
        assertEquals("1.1 背景", pieces.get(0).title(), "无祖先时路径退化为标题本身");
    }

    @Test
    void startsWithHeading_recognizesNonMarkdownTitle() {
        assertTrue(RecursiveChunkSplitter.splitWithCarry(
                "第一章 绪论\n\n正文。", 1, 800, 0, null, null).pieces().stream()
                .anyMatch(c -> c.title().equals("第一章 绪论")), "中文章节应被识别并开新块标题");
        assertFalse(RecursiveChunkSplitter.splitWithCarry(
                "这是普通段落。\n\n内容。", 1, 800, 0, null, null).pieces().stream()
                .anyMatch(c -> c.title() != null && !c.title().isBlank()), "普通段落不应产生标题");
    }

    // ===== 表格处理（A+B）=====

    @Test
    void mixedTextAndTable_tableProducedAsAtomicChunk() {
        String md = "## 3.1 设备清单\n\n正文说明。\n\n| 设备 | 数量 |\n|------|------|\n| 路由 | 2 |\n\n后续正文。";
        List<ChunkPiece> pieces = RecursiveChunkSplitter.split(md, 1, 800, 0);
        assertTrue(pieces.size() >= 2);
        // 表格原子块：内容含表头+分隔行+数据行，且与正文分离
        List<ChunkPiece> tables = pieces.stream().filter(c -> c.content().contains("| --- |")).toList();
        assertEquals(1, tables.size(), "应恰有一个表格块");
        assertTrue(tables.get(0).content().contains("| 设备 | 数量 |"));
        assertTrue(tables.get(0).content().contains("| 路由 | 2 |"));
        assertEquals("3.1 设备清单", tables.get(0).title(), "表格块 title 应取祖先链路径");
    }

    @Test
    void tableTitle_inheritsFullAncestorPath() {
        String md = "# 第一章\n\n## 3.1 设备清单\n\n| 设备 | 数量 |\n|------|------|\n| 路由 | 2 |";
        List<ChunkPiece> pieces = RecursiveChunkSplitter.split(md, 1, 800, 0);
        ChunkPiece table = pieces.stream().filter(c -> c.content().contains("| --- |")).findFirst().orElseThrow();
        assertEquals("第一章 > 3.1 设备清单", table.title());
    }

    @Test
    void largeTable_splitIntoRowGroupsEachWithHeader() {
        StringBuilder sb = new StringBuilder("| 序号 | 名称 | 说明 |\n|------|------|------|\n");
        for (int i = 0; i < 60; i++) {
            sb.append("| ").append(i).append(" | 项目").append(i).append(" | 说明内容用于填充表格行长度。 |\n");
        }
        List<ChunkPiece> pieces = RecursiveChunkSplitter.split(sb.toString(), 1, 200, 0);
        assertTrue(pieces.size() >= 3, "大表应切成多个行组");
        assertTrue(pieces.stream().allMatch(c -> c.content().startsWith("| 序号 | 名称 | 说明 |")),
                "每个行组都应带表头");
        assertTrue(pieces.stream().allMatch(c -> c.content().length() <= 200), "行组不应超过块长上限");
    }

    @Test
    void smallTable_oneAtomicChunk() {
        String md = "| a | b |\n|---|---|\n| 1 | 2 |";
        List<ChunkPiece> pieces = RecursiveChunkSplitter.split(md, 1, 800, 0);
        assertEquals(1, pieces.size(), "小表应整表一块");
        // toMarkdown 规范化分隔行格式（|---|---| → | --- | --- |）
        assertTrue(pieces.get(0).content().startsWith("| a | b |"), "应含表头");
        assertTrue(pieces.get(0).content().contains("| --- | --- |"), "应含分隔行");
        assertTrue(pieces.get(0).content().contains("| 1 | 2 |"), "应含数据行");
    }

    @Test
    void tableAfterHeading_headingStackUnaffected() {
        // 表格不更新标题栈：表格后的正文仍在原章节下
        String md = "## 3.1 设备清单\n\n| 设备 | 数量 |\n|------|------|\n| 路由 | 2 |\n\n正文继续。\n\n## 3.2 部署\n\n正文。";
        List<ChunkPiece> pieces = RecursiveChunkSplitter.split(md, 1, 800, 0);
        ChunkPiece table = pieces.stream().filter(c -> c.content().contains("| --- |")).findFirst().orElseThrow();
        assertEquals("3.1 设备清单", table.title());
        // 3.2 的正文块 title 应为 3.2（栈未被表格污染）
        ChunkPiece last = pieces.get(pieces.size() - 1);
        assertEquals("3.2 部署", last.title());
    }

    @Test
    void mergedCellTable_forwardFilledInChunk() {
        // 类别 a 跨多行颜色的合并单元格：表格块内应回填完整
        String md = "## 颜色表\n\n| 类别 | 颜色 |\n|------|------|\n| a | 红 |\n|   | 黄 |\n| b | 绿 |";
        List<ChunkPiece> pieces = RecursiveChunkSplitter.split(md, 1, 800, 0);
        ChunkPiece table = pieces.stream().filter(c -> c.content().contains("| --- |")).findFirst().orElseThrow();
        assertEquals("颜色表", table.title());
        assertTrue(table.content().contains("| a | 红 |"));
        assertTrue(table.content().contains("| a | 黄 |"), "合并单元格应 forward-fill 回填");
        assertTrue(table.content().contains("| b | 绿 |"));
    }
}
