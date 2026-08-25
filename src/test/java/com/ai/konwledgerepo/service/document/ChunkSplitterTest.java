package com.ai.konwledgerepo.service.document;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkSplitterTest {

    @Test
    void emptyText_returnsEmpty() {
        assertTrue(ChunkSplitter.split("", 0).isEmpty());
        assertTrue(ChunkSplitter.split(null, 0).isEmpty());
        assertTrue(ChunkSplitter.split("   \n  ", 0).isEmpty());
    }

    @Test
    void shortText_singleChunk() {
        List<ChunkPiece> result = ChunkSplitter.split("这是一段不长的文本。", 0);
        assertEquals(1, result.size());
        assertTrue(result.get(0).content().contains("文本"));
    }

    @Test
    void manyParagraphs_splitIntoMultipleChunks() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            sb.append("第").append(i).append("段，这是段落的内容，用于测试分块累积逻辑。\n\n");
        }
        List<ChunkPiece> result = ChunkSplitter.split(sb.toString(), 0);
        assertTrue(result.size() > 1, "100 段应被切为多块");
        assertTrue(result.stream().allMatch(c -> c.content().length() <= 800));
    }

    @Test
    void longParagraph_splitBySentence() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            sb.append("这是一个很长的句子用来测试切分逻辑是否正常工作。");
        }
        List<ChunkPiece> result = ChunkSplitter.split(sb.toString(), 3);
        assertTrue(result.size() > 1, "超长段落应按句子切为多块");
        assertTrue(result.stream().allMatch(c -> c.content().length() <= 800));
        assertTrue(result.stream().allMatch(c -> c.pageNum() == 3), "页码应透传");
    }

    @Test
    void customChunkSize_respected() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 50; i++) {
            sb.append("第").append(i).append("段，内容用于验证自定义块长上限是否生效。\n\n");
        }
        List<ChunkPiece> result = ChunkSplitter.split(sb.toString(), 0, 200, 0);
        assertTrue(result.size() > 1);
        assertTrue(result.stream().allMatch(c -> c.content().length() <= 200), "自定义 chunkSize=200 应生效");
    }

    @Test
    void overlap_repeatsTailOfPreviousChunk() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            sb.append("第").append(i).append("段，内容较长用于制造多个分块以便观察重叠效果。\n\n");
        }
        List<ChunkPiece> result = ChunkSplitter.split(sb.toString(), 0, 150, 40);
        assertTrue(result.size() > 1);
        // 下一块开头应包含上一块尾部字符（overlap 衔接）
        for (int i = 1; i < result.size(); i++) {
            String prevTail = result.get(i - 1).content();
            prevTail = prevTail.substring(Math.max(0, prevTail.length() - 40));
            String curHead = result.get(i).content().substring(0, Math.min(40, result.get(i).content().length()));
            assertTrue(curHead.contains(prevTail.substring(0, Math.min(10, prevTail.length()))),
                    "块间应有重叠上下文");
        }
    }

    @Test
    void markdownHeading_startsNewChunkWithTitle() {
        String text = "# 第一章 报销规则\n\n报销需要发票。\n\n## 差旅标准\n\n出差住宿标准为每天500元。\n\n报销流程：提交单据，等待审批。";
        List<ChunkPiece> result = ChunkSplitter.split(text, 0);
        assertTrue(result.size() >= 2, "标题应强制开新块");
        // 第一块归属 "第一章 报销规则"
        assertEquals("第一章 报销规则", result.get(0).title());
        // 后续块归属 "差旅标准"
        ChunkPiece last = result.get(result.size() - 1);
        assertEquals("差旅标准", last.title());
        // 标题行本身应保留在块内容中
        assertTrue(result.get(0).content().contains("第一章 报销规则"));
    }

    @Test
    void chineseChapterHeading_detected() {
        String text = "第一条 员工请假需提前申请\n\n请假天数超过三天需领导审批。";
        List<ChunkPiece> result = ChunkSplitter.split(text, 0);
        assertEquals("第一条 员工请假需提前申请", result.get(0).title());
        assertTrue(result.get(0).content().contains("第一条"));
    }

    @Test
    void sentenceEndingWithPunctuation_notTreatedAsHeading() {
        // 以句号结尾的完整句子不应被误判为条款标题
        List<ChunkPiece> result = ChunkSplitter.split("第一条知识内容。\n\n第二条知识内容，需要被正确分块。", 0);
        assertEquals(1, result.size(), "完整句子应合并为一块，而非被标题规则拆开");
    }

    @Test
    void headingWithoutBlankLineAfter_stillStartsNewChunk() {
        // 标题行与内容之间无空行（常见 Markdown 写法）也应正确识别标题并开新块
        String text = "# 员工手册\n考勤需每日打卡。\n## 报销规则\n报销需提供发票。\n## 年假规定\n入职满一年享五天年假。";
        List<ChunkPiece> result = ChunkSplitter.split(text, 0);
        assertEquals(3, result.size(), "三个标题应切出三块");
        assertEquals("员工手册", result.get(0).title());
        assertEquals("报销规则", result.get(1).title());
        assertEquals("年假规定", result.get(2).title());
    }

    @Test
    void bomPrefix_strippedBeforeHeadingDetection() {
        // Windows 保存的 txt/md 常带 UTF-8 BOM，不应影响首行标题识别
        String text = "\uFEFF# 员工手册\n考勤需每日打卡。\n## 报销规则\n报销需提供发票。";
        List<ChunkPiece> result = ChunkSplitter.split(text, 0);
        assertEquals("员工手册", result.get(0).title());
    }

    @Test
    void overlap_neverExceedsChunkSize() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 60; i++) {
            sb.append("第").append(i).append("段，内容用于验证重叠不会导致块超长。\n\n");
        }
        List<ChunkPiece> result = ChunkSplitter.split(sb.toString(), 0, 120, 60);
        assertTrue(result.stream().allMatch(c -> c.content().length() <= 120),
                "overlap 不应导致块超过 chunkSize");
    }

    /** 第一页：长内容产生多个块，且末尾块足够长（>120）以产生 carry */
    private static String pageOneText() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            sb.append("第").append(i).append("段，这是第一页的内容，用于制造多个分块。\n\n");
        }
        sb.append("第一页最后的段落，内容较长，确保最后一个分块超过重叠长度 120 字符，以便产生跨页重叠文本。");
        return sb.toString();
    }

    @Test
    void crossPage_carriesTailToNextPage() {
        ChunkSplitter.SplitResult sr1 = ChunkSplitter.splitWithCarry(pageOneText(), 1, 800, 120, null);
        assertFalse(sr1.pieces().isEmpty());
        assertFalse(sr1.carryOut().isEmpty(), "第一页末块足够长时应产出 carry");

        String page2 = "第二页正文，紧接第一页内容继续叙述后续主题。";
        ChunkSplitter.SplitResult sr2 = ChunkSplitter.splitWithCarry(page2, 2, 800, 120, sr1.carryOut());
        assertFalse(sr2.pieces().isEmpty());

        String lastOfPage1 = sr1.pieces().get(sr1.pieces().size() - 1).content();
        String firstOfPage2 = sr2.pieces().get(0).content();
        assertTrue(lastOfPage1.endsWith(sr1.carryOut()), "第一页末块应以 carry 结尾");
        assertTrue(firstOfPage2.startsWith(sr1.carryOut()), "第二页首块应携带第一页尾部重叠");
        assertTrue(firstOfPage2.length() > sr1.carryOut().length(), "第二页首块应包含 carry + 新内容");
    }

    @Test
    void crossPage_noCarryWhenNextStartsWithHeading() {
        ChunkSplitter.SplitResult sr1 = ChunkSplitter.splitWithCarry(pageOneText(), 1, 800, 120, null);
        assertFalse(sr1.carryOut().isEmpty());

        // 新页以标题开头：章节边界，不应携带上一页重叠
        String page2 = "# 新章节\n\n新章节正文内容。";
        ChunkSplitter.SplitResult sr2 = ChunkSplitter.splitWithCarry(page2, 2, 800, 120, sr1.carryOut());
        String firstOfPage2 = sr2.pieces().get(0).content();
        assertEquals("新章节", sr2.pieces().get(0).title(), "标题开头的页首块标题应为新章节");
        assertTrue(firstOfPage2.startsWith("# 新章节"), "标题开头的页首块应以标题行开头");
        assertFalse(firstOfPage2.startsWith(sr1.carryOut()), "标题开头的页不应携带上一页重叠");
    }

    @Test
    void crossPage_shortLastChunk_noCarryOut() {
        // 第一页只有一个短块（< overlap），不应产出 carry
        ChunkSplitter.SplitResult sr1 = ChunkSplitter.splitWithCarry("第一页只有一行很短的正文。", 1, 800, 120, null);
        assertEquals(1, sr1.pieces().size());
        assertEquals("", sr1.carryOut(), "末块短于 overlap 时不应产出 carry");

        String page2 = "第二页内容。";
        ChunkSplitter.SplitResult sr2 = ChunkSplitter.splitWithCarry(page2, 2, 800, 120, sr1.carryOut());
        assertEquals("第二页内容。", sr2.pieces().get(0).content(), "无 carry 时第二页首块应保持纯净");
    }

    @Test
    void crossPage_carryRespectsOverlapCap() {
        ChunkSplitter.SplitResult sr = ChunkSplitter.splitWithCarry(pageOneText(), 1, 800, 120, null);
        assertTrue(sr.carryOut().length() <= 120, "carry 不应超过 overlap");

        // overlap 超过 chunkSize/2 时按上限截断
        ChunkSplitter.SplitResult srBig = ChunkSplitter.splitWithCarry(pageOneText(), 1, 800, 500, null);
        assertTrue(srBig.carryOut().length() <= 400, "carry 不应超过 chunkSize/2 上限");
    }

    @Test
    void crossPage_blankNextPage_passesCarryThrough() {
        ChunkSplitter.SplitResult sr1 = ChunkSplitter.splitWithCarry(pageOneText(), 1, 800, 120, null);
        assertFalse(sr1.carryOut().isEmpty());

        // 空页：不打断 carry，透传给再下一页
        ChunkSplitter.SplitResult blank = ChunkSplitter.splitWithCarry("   \n  ", 2, 800, 120, sr1.carryOut());
        assertTrue(blank.pieces().isEmpty());
        assertEquals(sr1.carryOut(), blank.carryOut(), "空页应透传 carry");
    }
}