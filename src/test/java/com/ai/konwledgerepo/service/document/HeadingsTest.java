package com.ai.konwledgerepo.service.document;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HeadingsTest {

    // ===== parse：类型识别与层级推断 =====

    @Test
    void parse_markdownHeading_levelEqualsHashCount() {
        assertEquals(new Headings.Heading(1, "第一章 绪论"), Headings.parse("# 第一章 绪论"));
        assertEquals(new Headings.Heading(2, "1.1 背景"), Headings.parse("## 1.1 背景"));
        assertEquals(new Headings.Heading(6, "深层"), Headings.parse("###### 深层"));
    }

    @Test
    void parse_chineseChapter_level1() {
        assertEquals(new Headings.Heading(1, "第一章 绪论"), Headings.parse("第一章 绪论"));
        assertEquals(new Headings.Heading(1, "第2篇 设计"), Headings.parse("第2篇 设计"));
        assertEquals(new Headings.Heading(1, "第四条"), Headings.parse("第四条"));
    }

    @Test
    void parse_chineseItem_level2() {
        assertEquals(new Headings.Heading(2, "一、概述"), Headings.parse("一、概述"));
        assertEquals(new Headings.Heading(2, "二. 细节"), Headings.parse("二. 细节"));
        assertEquals(new Headings.Heading(2, "三．补充"), Headings.parse("三．补充"));
    }

    @Test
    void parse_numberedItem_levelFromSegments() {
        assertEquals(new Headings.Heading(1, "1. 概述"), Headings.parse("1. 概述"));
        assertEquals(new Headings.Heading(2, "1.1 背景"), Headings.parse("1.1 背景"));
        assertEquals(new Headings.Heading(3, "1.1.1 意义"), Headings.parse("1.1.1 意义"));
        // 单个数字段 + 顿号分隔 = 1 级（与 "1. 概述" 同层）
        assertEquals(new Headings.Heading(1, "1、定义"), Headings.parse("1、定义"));
        assertEquals(new Headings.Heading(2, "1.1、补充"), Headings.parse("1.1、补充"));
    }

    @Test
    void parse_sentenceEndingWithPunctuation_notHeading() {
        assertNull(Headings.parse("第一章 内容是完整句子。"));
        assertNull(Headings.parse("1. 这是完整句子。"));
        assertNull(Headings.parse("一、完整句子！"));
        assertNull(Headings.parse("这是普通正文段落内容。"));
        assertNull(Headings.parse(null));
        assertNull(Headings.parse(""));
        assertNull(Headings.parse("   \n  "));
    }

    // ===== apply：祖先栈更新 =====

    @Test
    void apply_nestedHeadings_buildStack() {
        List<Headings.StackEntry> stack = new ArrayList<>();
        stack = Headings.apply(stack, Headings.parse("# A"));
        stack = Headings.apply(stack, Headings.parse("## B"));
        stack = Headings.apply(stack, Headings.parse("### C"));
        assertEquals(List.of(
                        new Headings.StackEntry(1, "A"),
                        new Headings.StackEntry(2, "B"),
                        new Headings.StackEntry(3, "C")),
                stack);
    }

    @Test
    void apply_siblingHeading_replacesSubLevel() {
        List<Headings.StackEntry> stack = new ArrayList<>();
        stack = Headings.apply(stack, Headings.parse("# A"));
        stack = Headings.apply(stack, Headings.parse("## B1"));
        stack = Headings.apply(stack, Headings.parse("## B2"));
        assertEquals(List.of(new Headings.StackEntry(1, "A"), new Headings.StackEntry(2, "B2")), stack);
    }

    @Test
    void apply_siblingWithoutRoot_replacesStack() {
        // 无根文档（全 ## 同级）：同级标题应替换，而非错误嵌套为父子
        List<Headings.StackEntry> stack = new ArrayList<>();
        stack = Headings.apply(stack, Headings.parse("## 3.1 设备清单"));
        stack = Headings.apply(stack, Headings.parse("## 3.2 部署"));
        assertEquals(List.of(new Headings.StackEntry(2, "3.2 部署")), stack);
        assertEquals("3.2 部署", Headings.path(stack));
    }

    @Test
    void apply_jumpUp_resetsFromNewRoot() {
        List<Headings.StackEntry> stack = new ArrayList<>();
        stack = Headings.apply(stack, Headings.parse("## B")); // 无祖先时从空栈建（路径退化为单级）
        stack = Headings.apply(stack, Headings.parse("# C"));  // 更高级标题 → 清空重建
        assertEquals(List.of(new Headings.StackEntry(1, "C")), stack);
    }

    @Test
    void apply_skipLevel_keepsShallowerAncestors() {
        // # A 后直接 ### C：跳级时保留更浅的 A 作祖先（C 挂 A 下）
        List<Headings.StackEntry> stack = new ArrayList<>();
        stack = Headings.apply(stack, Headings.parse("# A"));
        stack = Headings.apply(stack, Headings.parse("### C"));
        assertEquals(List.of(new Headings.StackEntry(1, "A"), new Headings.StackEntry(3, "C")), stack);
    }

    @Test
    void apply_mixedStyles_sharedStack() {
        List<Headings.StackEntry> stack = new ArrayList<>();
        stack = Headings.apply(stack, Headings.parse("# 员工手册"));
        stack = Headings.apply(stack, Headings.parse("一、考勤"));   // level 2
        stack = Headings.apply(stack, Headings.parse("1.1 打卡规则")); // level 2 → 同级替换
        assertEquals(List.of(
                        new Headings.StackEntry(1, "员工手册"),
                        new Headings.StackEntry(2, "1.1 打卡规则")),
                stack);
    }

    @Test
    void apply_levelCap_stackDepth() {
        List<Headings.StackEntry> stack = new ArrayList<>();
        for (int i = 1; i <= 8; i++) {
            stack = Headings.apply(stack, new Headings.Heading(i, "L" + i));
        }
        assertTrue(stack.size() <= Headings.MAX_STACK, "栈深不应超过 " + Headings.MAX_STACK);
        assertEquals("L8", stack.get(stack.size() - 1).text());
    }

    @Test
    void apply_overLengthText_truncated() {
        String longText = "很长的标题".repeat(30); // 150 字符
        List<Headings.StackEntry> stack = Headings.apply(new ArrayList<>(), new Headings.Heading(1, longText));
        assertEquals(Headings.MAX_LEVEL_TEXT, stack.get(0).text().length());
    }

    // ===== path：拼接与截断 =====

    @Test
    void path_joinWithSeparator() {
        assertEquals("A > B > C", Headings.path(List.of(
                new Headings.StackEntry(1, "A"),
                new Headings.StackEntry(2, "B"),
                new Headings.StackEntry(3, "C"))));
        assertEquals("员工手册 > 一、考勤 > 1.1 打卡规则",
                Headings.path(List.of(
                        new Headings.StackEntry(1, "员工手册"),
                        new Headings.StackEntry(2, "一、考勤"),
                        new Headings.StackEntry(2, "1.1 打卡规则"))));
        assertEquals("", Headings.path(List.of()));
        assertEquals("", Headings.path(null));
    }

    @Test
    void path_overLimit_dropsOldestLevels() {
        List<Headings.StackEntry> stack = List.of(
                new Headings.StackEntry(1, "根".repeat(70)),
                new Headings.StackEntry(2, "中".repeat(70)),
                new Headings.StackEntry(3, "叶".repeat(70)));
        String p = Headings.path(stack);
        assertTrue(p.length() <= Headings.MAX_TITLE, "路径应被截断到上限内: len=" + p.length());
        assertFalse(p.contains("根"), "超限时应优先丢弃最老层级（保留最近标题）");
        assertTrue(p.contains("叶"), "最近层级应保留");
    }
}
