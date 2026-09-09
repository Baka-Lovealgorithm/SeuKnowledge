package com.ai.konwledgerepo.graph;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RefSnippet} 表格感知截断单测。
 */
class RefSnippetTest {

    private static final String TABLE = """
            | 操作系统 | 系统最低版本 | 依赖环境 |
            | --- | --- | --- |
            | Windows | Windows XP | Visual Studio 2008 及以上 |
            | Linux | 2.6.0 以上 | g++ 4.8 以上版本 |
            """;

    @Test
    void nullReturnsEmpty() {
        assertEquals("", RefSnippet.snippet(null));
    }

    @Test
    void shortContentReturnedAsIs() {
        String s = "短内容";
        assertEquals(s, RefSnippet.snippet(s));
    }

    @Test
    void exactlyMaxLengthReturnedAsIs() {
        String s = "x".repeat(RefSnippet.MAX);
        assertEquals(s, RefSnippet.snippet(s));
    }

    @Test
    void plainLongTextTruncatedWithEllipsis() {
        String s = "中文字符内容".repeat(100); // 600 字 > 300
        String out = RefSnippet.snippet(s);
        assertEquals(RefSnippet.MAX + 1, out.length()); // 300 + "…"
        assertTrue(out.endsWith("…"));
        assertEquals(s.substring(0, RefSnippet.MAX), out.substring(0, RefSnippet.MAX));
    }

    @Test
    void tableFullyPreservedWhenCutInsideTable() {
        // 表格 100 行，远超 300 字上限，截断点必然落在表格内 → 整表返回
        StringBuilder big = new StringBuilder("前置说明\n\n");
        big.append(TABLE);
        for (int i = 0; i < 100; i++) {
            big.append("| 行").append(i).append(" | 值").append(i).append(" | - |\n");
        }
        String content = big.toString();
        String out = RefSnippet.snippet(content);
        // 整表保留：从表头行开始，含最后一行，不含前置说明，无省略号
        assertTrue(out.trim().startsWith("| 操作系统 | 系统最低版本 | 依赖环境 |"));
        assertTrue(out.contains("| 行99 | 值99 | - |"));
        assertFalse(out.contains("前置说明"));
        assertFalse(out.endsWith("…"));
    }

    @Test
    void tablePreservedWhenCutPointInHeaderSeparator() {
        // 表格前放一段文字把截断点推进到表格内
        String prefix = "甲".repeat(290) + "\n"; // 表格起始于 ~291
        String content = prefix + TABLE;
        String out = RefSnippet.snippet(content, 300);
        // 截断点 300 落在表格块内 → 整表返回（含前缀文本块之后的完整表格）
        assertTrue(out.contains("| 操作系统 | 系统最低版本 | 依赖环境 |"));
        assertTrue(out.contains("| Linux | 2.6.0 以上 | g++ 4.8 以上版本 |"));
    }

    @Test
    void plainTruncationWhenCutBeforeTable() {
        String content = "甲".repeat(400) + "\n\n" + TABLE;
        // 截断点 300 在表格之前（纯文本内）→ 普通截断，不含表格
        String out = RefSnippet.snippet(content, 300);
        assertEquals(301, out.length());
        assertFalse(out.contains("| 操作系统"));
    }

    @Test
    void multipleTablesOnlyHitOneKept() {
        // 第一张表重复放大到远超 300 字，截断点落在其中；第二张表位于其后不应被截到
        String bigTable = TABLE.repeat(60);
        String content = bigTable + "\n正文隔断\n" + TABLE;
        String out = RefSnippet.snippet(content, 300);
        // 返回第一张表整块（以表头行开头、含最后一行、不含隔断与第二张表）
        assertTrue(out.trim().startsWith("| 操作系统 | 系统最低版本 | 依赖环境 |"));
        assertTrue(out.contains("| Linux | 2.6.0 以上 | g++ 4.8 以上版本 |"));
        assertFalse(out.contains("正文隔断"));
        // 截断点 300 落在大表内 → 整块保留，无省略号
        assertFalse(out.endsWith("…"));
    }

    @Test
    void singleColumnTablePreserved() {
        String single = """
                | 库文件 |
                | --- |
                | ZRDDSCppzd.lib |
                """.repeat(40); // 远超 300
        String out = RefSnippet.snippet(single);
        assertTrue(out.trim().startsWith("| 库文件 |"));
        assertTrue(out.contains("ZRDDSCppzd.lib"));
    }

    @Test
    void tableInsideCellWithBrKeptAsWhole() {
        String content = """
                | 名称 | 说明 |
                | --- | --- |
                | 空项目 | 类型: Visual C++<br/>用于创建本地应用程序 |
                """;
        // 短表原样返回
        assertEquals(content.trim(), RefSnippet.snippet(content).trim());
    }

    @Test
    void emptyContentReturnsEmpty() {
        assertEquals("", RefSnippet.snippet(""));
    }

    // ===== 围栏代码块感知 =====

    private static final String CODE = """
            ```java
            public class Demo {
                public static void main(String[] args) {
                    System.out.println("hello zrdds demo");
                }
            }
            ```
            """;

    @Test
    void fenceBlockFullyPreservedWhenCutInside() {
        // 前缀把截断点推进到代码块中部 → 整块围栏返回，无省略号、不含前缀与后继文本
        String content = "前".repeat(290) + "\n\n" + CODE + "\n后续内容" + "末".repeat(300);
        String out = RefSnippet.snippet(content);
        assertTrue(out.trim().startsWith("```java"), "应返回整块代码，实际: " + out);
        assertTrue(out.trim().endsWith("```"));
        assertFalse(out.contains("后续内容"), "整块只含围栏本体");
        assertFalse(out.contains("前前前"), "不含前缀文本");
        assertFalse(out.endsWith("…"));
    }

    @Test
    void pipeLinesInsideFenceKeptAsCodeNotTable() {
        // 围栏内含 | 行：结构认领围栏优先，返回整块代码而非其中的 | 行片段
        String fence = "```ini\n| key | value |\n|-----|-------|\n" + "| row | val |\n".repeat(30) + "```";
        String content = "x".repeat(250) + "\n" + fence + "\n" + "y".repeat(200);
        // 截断点 300 落在围栏内
        String out = RefSnippet.snippet(content, 300);
        assertTrue(out.trim().startsWith("```ini"), "应返回整块围栏代码，实际开头: " + out.substring(0, Math.min(60, out.length())));
        assertTrue(out.trim().endsWith("```"));
    }

    @Test
    void unclosedFenceExtendsToTextEnd() {
        // 无闭栏（跨页截断产物）：围栏块延伸至文本尾
        String content = "a".repeat(250) + "\n```python\nprint(1)\nprint(2)\n" + "b".repeat(200);
        String out = RefSnippet.snippet(content, 300);
        assertTrue(out.trim().startsWith("```python"));
        assertTrue(out.contains("b".repeat(50)), "未闭合围栏应延伸至文本尾");
    }

    @Test
    void plainTextBeforeFenceStillTruncated() {
        // 截断点在围栏之前的普通文本内 → 普通截断，不整块返回代码
        String content = "z".repeat(400) + "\n" + CODE;
        String out = RefSnippet.snippet(content, 300);
        assertEquals(301, out.length());
        assertFalse(out.contains("```java"));
    }

    @Test
    void maxZeroReturnsWhole() {
        assertEquals("abc", RefSnippet.snippet("abc", 0));
    }
}
