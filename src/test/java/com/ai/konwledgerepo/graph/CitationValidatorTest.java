package com.ai.konwledgerepo.graph;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 答案引用编号校验（CitationValidator）单元测试：
 * 提取编号、最大编号、越界识别、越界移除（保正文去标记、无越界返回原串）。
 */
class CitationValidatorTest {

    @Test
    void extract_singleCitation() {
        assertArrayEquals(new int[]{1}, CitationValidator.extract("根据[1]所述"));
    }

    @Test
    void extract_multipleCitations() {
        assertArrayEquals(new int[]{1, 2, 3}, CitationValidator.extract("见[1][2][3]"));
    }

    @Test
    void extract_noCitation_returnsEmpty() {
        assertArrayEquals(new int[0], CitationValidator.extract("没有引用标记的答案"));
        assertArrayEquals(new int[0], CitationValidator.extract(null));
        assertArrayEquals(new int[0], CitationValidator.extract("  "));
    }

    @Test
    void extract_bracketsWithoutNumber_ignored() {
        assertArrayEquals(new int[0], CitationValidator.extract("文本[abc]内容"));
    }

    @Test
    void maxCitation_returnsLargest() {
        assertEquals(5, CitationValidator.maxCitation("见[2]和[5]"));
        assertEquals(0, CitationValidator.maxCitation("无引用"));
    }

    @Test
    void hasOutOfRange_detectsOverflow() {
        assertTrue(CitationValidator.hasOutOfRange("引用[5]越界", 4), "refs=4 时 [5] 应判定越界");
        assertFalse(CitationValidator.hasOutOfRange("引用[4]合法", 4), "refs=4 时 [4] 合法");
    }

    @Test
    void hasOutOfRange_detectsZero() {
        assertTrue(CitationValidator.hasOutOfRange("引用[0]非法", 4), "[0] 应判定越界");
    }

    @Test
    void stripOutOfRange_removesOverflowKeepsTextAndValidRefs() {
        String cleaned = CitationValidator.stripOutOfRange("根据[5]所述[1]，需填写表单", 4);
        assertEquals("根据所述[1]，需填写表单", cleaned, "越界 [5] 应被移除，正文与合法 [1] 保留");
    }

    @Test
    void stripOutOfRange_noOutOfRange_returnsOriginalInstance() {
        String answer = "根据[1][2]所述";
        assertSame(answer, CitationValidator.stripOutOfRange(answer, 2), "无越界时应返回原串（不复制）");
    }

    @Test
    void stripOutOfRange_allCitationsInvalid_removesAllMarkers() {
        assertEquals("正文内容", CitationValidator.stripOutOfRange("正文[9]内容[10]", 2));
    }

    @Test
    void stripOutOfRange_nullOrBlank_returnsAsIs() {
        assertSame(null, CitationValidator.stripOutOfRange(null, 2));
        assertEquals("", CitationValidator.stripOutOfRange("", 2));
    }
}
