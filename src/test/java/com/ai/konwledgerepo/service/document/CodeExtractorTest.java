package com.ai.konwledgerepo.service.document;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CodeExtractor} 围栏状态机单测：开闭栏识别、原文逐行保真、未闭合自愈、
 * 与表格识别的行级优先级共存。
 */
class CodeExtractorTest {

    @Test
    void proseAndFence_splitInOrder() {
        String md = "前文说明。\n\n```java\npublic class Demo {\n    int x = 1;\n}\n```\n\n后文说明。";
        List<CodeExtractor.Segment> segs = CodeExtractor.extract(md);
        assertEquals(3, segs.size());
        assertTrue(segs.get(0) instanceof CodeExtractor.ProseBlock);
        CodeExtractor.CodeBlock code = (CodeExtractor.CodeBlock) segs.get(1);
        assertEquals("```", code.marker());
        assertEquals("java", code.info());
        assertEquals(List.of("public class Demo {", "    int x = 1;", "}"), code.lines(),
                "围栏内逐行原样保留（含缩进）");
        assertTrue(code.closed());
        assertTrue(segs.get(2) instanceof CodeExtractor.ProseBlock);
    }

    @Test
    void noFence_singleProse() {
        List<CodeExtractor.Segment> segs = CodeExtractor.extract("纯正文。\n\n第二段。");
        assertEquals(1, segs.size());
        assertTrue(segs.get(0) instanceof CodeExtractor.ProseBlock);
    }

    @Test
    void tildeFence_recognized() {
        List<CodeExtractor.Segment> segs = CodeExtractor.extract("~~~sh\nls -la\n~~~");
        assertEquals(1, segs.size());
        CodeExtractor.CodeBlock code = (CodeExtractor.CodeBlock) segs.get(0);
        assertEquals("~~~", code.marker());
        assertEquals("sh", code.info());
        assertEquals(List.of("ls -la"), code.lines());
    }

    @Test
    void unclosedFence_emitsUnclosedAndKeepsPriorProse() {
        // LlamaParse 逐页产物页尾常见：开栏后本段结束
        List<CodeExtractor.Segment> segs = CodeExtractor.extract("前文。\n\n```java\nint a = 1;");
        assertEquals(2, segs.size());
        CodeExtractor.CodeBlock code = (CodeExtractor.CodeBlock) segs.get(1);
        assertFalse(code.closed(), "页尾未闭合应标记 closed=false");
        assertEquals(List.of("int a = 1;"), code.lines());
    }

    @Test
    void indentedFenceLine_recognized() {
        // 列表/引用内的缩进围栏也要认（LlamaParse 常见输出形态），体行保留自身缩进
        List<CodeExtractor.Segment> segs = CodeExtractor.extract("- 列表项\n\n  ```python\nprint(1)\n  ```");
        assertEquals(2, segs.size());
        CodeExtractor.CodeBlock code = (CodeExtractor.CodeBlock) segs.get(1);
        assertEquals(List.of("print(1)"), code.lines());
    }

    @Test
    void backtickInInfo_notAnOpening() {
        // CommonMark：反引号围栏 info 含反引号不构成开栏 → 整段都是正文
        List<CodeExtractor.Segment> segs = CodeExtractor.extract("a\n``` `x` \nb");
        assertEquals(1, segs.size());
        assertTrue(segs.get(0) instanceof CodeExtractor.ProseBlock);
    }

    @Test
    void infoLineInsideFence_isBodyContent_notCloser() {
        // 带 info 的围栏行在围栏内是普通代码行，只有"纯围栏字符行"才闭栏
        List<CodeExtractor.Segment> segs = CodeExtractor.extract("```java\nclass A {\n```java\nint x;\n}\n```");
        assertEquals(1, segs.size());
        CodeExtractor.CodeBlock code = (CodeExtractor.CodeBlock) segs.get(0);
        assertTrue(code.closed());
        assertEquals(List.of("class A {", "```java", "int x;", "}"), code.lines());
    }

    @Test
    void closerShorterThanOpener_notClosing() {
        // 4 反引号开栏须 ≥4 反引号才闭：内部 3 反引号行是内容
        List<CodeExtractor.Segment> segs = CodeExtractor.extract("````py\n```\ncode\n````");
        assertEquals(1, segs.size());
        CodeExtractor.CodeBlock code = (CodeExtractor.CodeBlock) segs.get(0);
        assertEquals("````", code.marker());
        assertEquals(List.of("```", "code"), code.lines());
        assertTrue(code.closed());
    }

    @Test
    void pipeTableLinesInsideFence_stayCodeLines() {
        // 共存关键：围栏内 ASCII 表不被交给 TableExtractor（本层围栏优先认领）
        String md = "```\n| a | b |\n|---|---|\n| 1 | 2 |\n```";
        List<CodeExtractor.Segment> segs = CodeExtractor.extract(md);
        assertEquals(1, segs.size());
        CodeExtractor.CodeBlock code = (CodeExtractor.CodeBlock) segs.get(0);
        assertEquals(List.of("| a | b |", "|---|---|", "| 1 | 2 |"), code.lines(),
                "pipe 行逐字保留为代码行，表格识别看不到它们");
    }

    @Test
    void multipleFencesAndEmptyInfo_supported() {
        List<CodeExtractor.Segment> segs = CodeExtractor.extract("```\nplain\n```\n中间正文\n~~~\n~~~\n尾段");
        assertEquals(4, segs.size());
        assertTrue(segs.get(0) instanceof CodeExtractor.CodeBlock cb && cb.info().isEmpty());
        assertTrue(segs.get(1) instanceof CodeExtractor.ProseBlock);
        assertTrue(segs.get(2) instanceof CodeExtractor.CodeBlock cb && cb.lines().isEmpty(),
                "空代码块照常产出段（由 codePieces 决定丢弃）");
        assertTrue(segs.get(3) instanceof CodeExtractor.ProseBlock);
    }

    @Test
    void toMarkdown_reconstructsFullFencedBlock() {
        CodeExtractor.CodeBlock code = new CodeExtractor.CodeBlock("```", "java",
                List.of("int a;", "    int b;"), true);
        String md = code.toMarkdown();
        assertTrue(md.startsWith("```java\n"), md);
        assertTrue(md.endsWith("\n```"), md);
        assertTrue(md.contains("int a;\n    int b;"), "体行缩进逐字保留");
    }
}
