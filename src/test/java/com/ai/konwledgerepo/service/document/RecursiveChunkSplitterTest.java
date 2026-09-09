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

    // ===== 代码围栏感知（A+B）=====

    @Test
    void smallCodeBlock_atomicChunk_keepsFenceAndTitle() {
        String md = "## 3.1 示例\n\n说明文字。\n\n```java\nint a = 1;\nSystem.out.println(a);\n```\n\n后续说明。";
        List<ChunkPiece> pieces = RecursiveChunkSplitter.split(md, 1, 800, 0);
        List<ChunkPiece> codeChunks = pieces.stream().filter(p -> p.content().startsWith("```")).toList();
        assertEquals(1, codeChunks.size(), "小代码块应整块原子");
        ChunkPiece code = codeChunks.get(0);
        assertTrue(code.content().endsWith("```"), "闭栏应保留");
        assertTrue(code.content().contains("System.out.println(a);"), "代码体逐字保留");
        assertEquals("3.1 示例", code.title(), "代码块 title 应为祖先链路径");
        // 代码块与前后正文不同块
        assertTrue(pieces.stream().anyMatch(p -> p.content().contains("说明文字") && !p.content().contains("int a = 1;")),
                "前文应独立成块");
    }

    @Test
    void largeCodeBlock_rowGroups_repeatFence_andConserveLines() {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            body.append("    service.step").append(i).append("(context, options, callback); // 步骤 ").append(i).append("\n");
            if (i % 8 == 7) {
                body.append("\n");
            }
        }
        String md = "## 大示例\n\n```java\n" + body + "```\n";
        List<ChunkPiece> pieces = RecursiveChunkSplitter.split(md, 1, 200, 0);
        List<ChunkPiece> groups = pieces.stream().filter(p -> p.content().startsWith("```")).toList();
        assertTrue(groups.size() >= 5, "大代码块应切成多行组，实际 " + groups.size());
        String recon = new String();
        for (ChunkPiece g : groups) {
            assertTrue(g.content().endsWith("\n```") || g.content().endsWith("```"), "行组应自成围栏: " + g.content());
            assertTrue(g.content().length() <= 200, "行组不应超上限，实际 " + g.content().length());
            assertEquals("大示例", g.title());
            String[] lines = g.content().split("\n");
            for (int k = 1; k < lines.length - 1; k++) {
                if (!lines[k].isBlank()) {
                    recon = recon.isEmpty() ? lines[k] : recon + "\n" + lines[k];
                }
            }
        }
        // 非空代码行全量保真、原顺序、不重复（组间无 overlap）
        String expected = body.toString().lines().filter(l -> !l.isBlank()).reduce("", (a, b) -> a.isEmpty() ? b : a + "\n" + b);
        assertEquals(expected, recon, "代码行应跨组守恒且按原顺序");
    }

    @Test
    void largeCodeBlock_prefersBlankLineAsGroupBoundary() {
        // 两个方法块（块间空行分隔），行组应在空行处分界而非把方法劈开
        String methodA = "    void methodA() {\n        doWorkA(arg1, arg2);\n    }";
        String methodB = "    void methodB() {\n        doWorkB(arg1, arg2);\n    }";
        String md = "```java\n" + methodA + "\n\n" + methodB + "\n```";
        List<ChunkPiece> pieces = RecursiveChunkSplitter.split(md, 1, 60, 0);
        List<ChunkPiece> groups = pieces.stream().filter(p -> p.content().startsWith("```")).toList();
        assertTrue(groups.size() >= 2);
        boolean cleanBoundary = groups.stream().noneMatch(g -> g.content().contains("methodA") && g.content().contains("methodB"));
        assertTrue(cleanBoundary, "组界应对齐空行块边界，不把两个方法混进一组");
    }

    @Test
    void headingStackFreezeInsideCodeBlock() {
        // 围栏内 # 注释行不得污染标题栈：代码块与后续正文都应留在原章节下
        String md = "# 部署\n\n```python\n# 初始化环境\npip install zrdds\n```\n\n继续正文说明。";
        List<ChunkPiece> pieces = RecursiveChunkSplitter.split(md, 1, 800, 0);
        assertTrue(pieces.stream().allMatch(p -> "部署".equals(p.title())),
                "所有块 title 应为 部署，实际: " + pieces.stream().map(ChunkPiece::title).toList());
    }

    @Test
    void asciiTableInsideCodeBlock_notTableified() {
        // 共存关键：围栏内 ASCII 表不得被 TableExtractor 认领（不规整化、不 forward-fill）
        String md = "## 配置\n\n```ini\n[key]\n|a|b|\n|-|-|\n|x||\n```\n\n正文。";
        List<ChunkPiece> pieces = RecursiveChunkSplitter.split(md, 1, 800, 0);
        ChunkPiece code = pieces.stream().filter(p -> p.content().startsWith("```")).findFirst().orElseThrow();
        assertTrue(code.content().contains("|a|b|"), "围栏内 pipe 行应逐字保留，不重排为表格格式");
        assertFalse(code.content().contains("| --- |"), "不应出现表格规整化分隔行");
        assertFalse(code.content().contains("| x | b |"), "不应发生 forward-fill");
    }

    @Test
    void codeAndTableAndText_mixedOrderPreserved() {
        // 正文 → 表格 → 代码 → 正文 混排：产出顺序与文档顺序一致
        String md = "前段正文。\n\n| 列A | 列B |\n|---|---|\n| 1 | 2 |\n\n```c\nint x = 1;\n```\n\n尾段正文。";
        List<ChunkPiece> pieces = RecursiveChunkSplitter.split(md, 1, 800, 0);
        int text1 = firstMatch(pieces, p -> p.content().contains("前段正文"));
        int table = firstMatch(pieces, p -> p.content().contains("| --- |"));
        int code = firstMatch(pieces, p -> p.content().startsWith("```c"));
        int text2 = firstMatch(pieces, p -> p.content().contains("尾段正文"));
        assertTrue(text1 >= 0 && table > text1 && code > table && text2 > code,
                "混排顺序应为 正文<表格<代码<正文，实际 " + text1 + "," + table + "," + code + "," + text2);
    }

    @Test
    void overlapNeverLeaksIntoCodeChunks() {
        // 前置正文（含 overlap）+ 代码块：代码块首组必须从围栏开始，不携带正文尾巴
        String longText = "这是足够长的正文内容用于触发窗口溢出与重叠。".repeat(20);
        String md = longText + "\n\n```java\nint a = 1;\n```\n\n后续正文。";
        List<ChunkPiece> pieces = RecursiveChunkSplitter.split(md, 1, 150, 40);
        List<ChunkPiece> code = pieces.stream().filter(p -> p.content().contains("int a = 1;")).toList();
        assertEquals(1, code.size());
        assertTrue(code.get(0).content().startsWith("```java"), "代码块不得携带 overlap 前缀，实际: " + code.get(0).content());
    }

    @Test
    void unclosedFence_synthesizesClose_andNoCarry() {
        // 页尾未闭合围栏：块内合成闭栏自愈，不向下一页遗留 carry
        String page = "```java\nint a = 1;\nint b = 2;";
        RecursiveChunkSplitter.SplitResult sr = RecursiveChunkSplitter.splitWithCarry(page, 1, 800, 0, null, null);
        assertEquals(1, sr.pieces().size());
        assertTrue(sr.pieces().get(0).content().endsWith("```"), "未闭合围栏应合成闭栏");
        assertTrue(sr.pieces().get(0).content().startsWith("```java"));
        assertEquals("", sr.carryOut(), "结构块边界不应产生 carry");
    }

    private static int firstMatch(List<ChunkPiece> pieces, java.util.function.Predicate<ChunkPiece> p) {
        for (int i = 0; i < pieces.size(); i++) {
            if (p.test(pieces.get(i))) {
                return i;
            }
        }
        return -1;
    }
}
