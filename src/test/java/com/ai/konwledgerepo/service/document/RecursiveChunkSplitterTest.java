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
        assertEquals(List.of("第一章"), sr1.lastStack(), "第一页末标题栈");

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
        assertEquals(List.of("第一章"), blank.lastStack(), "空页应透传标题栈");
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
}
