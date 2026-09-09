package com.ai.konwledgerepo.entity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「类型 × 用途」组合矩阵。
 * <p>规则：<b>类型 = 不可互相顶替的调用契约</b>（CHAT/VISION/EMBEDDING/RERANK），
 * <b>用途 = 同契约内的角色槽位</b>（可互相顶替，只是成本/质量差异）。
 * 故只有 CHAT 契约下存在多用途；TITLE 已并入 CHAT（历史 TITLE 类型行仅保留兼容）。
 */
class ModelTypeTest {

    @ParameterizedTest
    @CsvSource({
            "CHAT, EXTRACT", "CHAT, GENERATE", "CHAT, VERIFY", "CHAT, ROUTER",
            "CHAT, MEMORY", "CHAT, CHITCHAT", "CHAT, TITLE",
            "EMBEDDING, RETRIEVE", "VISION, VISION", "RERANK, RERANK",
            "TITLE, TITLE"
    })
    void legalCombinations(String type, String usage) {
        assertTrue(ModelType.of(type).validUsage(usage), type + " 应支持用途 " + usage);
    }

    @ParameterizedTest
    @CsvSource({
            // 识图/向量/重排的调用契约不同，不接受文本角色用途
            "VISION, TITLE", "VISION, GENERATE", "RERANK, VERIFY", "RERANK, RETRIEVE", "TITLE, GENERATE",
            // 向量模型不得按角色拆分（入库与检索必须同一枚）：任何"第二个向量用途"都非法
            "EMBEDDING, INGEST", "EMBEDDING, QUERY", "EMBEDDING, GENERATE",
            // 未知用途
            "CHAT, FOO", "CHAT, ASR"
    })
    void illegalCombinations(String type, String usage) {
        assertFalse(ModelType.of(type).validUsage(usage), type + " 不应支持用途 " + usage);
    }

    /** 用途留空 = 该类型「通用」行，任何类型都合法（解析链第 2 档就是它） */
    @ParameterizedTest
    @EnumSource(ModelType.class)
    void blankUsageAlwaysLegal(ModelType type) {
        assertTrue(type.validUsage(null), type + " 应接受空用途（通用）");
        assertTrue(type.validUsage("  "), type + " 应接受空白用途（通用）");
    }

    /** 四个契约类型必须齐备；TITLE 作为历史常量仍能被 of() 认出来（存量行不得因枚举删值而解析失败） */
    @Test
    void contractTypes_andLegacyTitleStillRecognized() {
        for (String type : new String[]{"CHAT", "EMBEDDING", "VISION", "RERANK"}) {
            assertNotNull(ModelType.of(type), type + " 必须是已知类型");
            assertEquals(type, ModelType.of(type).value());
        }
        assertSame(ModelType.TITLE, ModelType.of("TITLE"), "历史类型 TITLE 仍需可解析（兼容存量行）");
        assertNull(ModelType.of("ASR"), "未登记的能力契约不应被认成类型");
    }
}
