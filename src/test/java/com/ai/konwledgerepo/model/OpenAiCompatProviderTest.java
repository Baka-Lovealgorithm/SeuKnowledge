package com.ai.konwledgerepo.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * OpenAI 兼容 baseUrl 归一化：剥离末尾 /v1（Spring AI 会自动追加 /v1/chat/completions，
 * 若用户填了带 /v1 的地址会拼成 /v1/v1/... 导致 404「接口不存在」）。
 */
class OpenAiCompatProviderTest {

    @Test
    void normalize_keepsBareHost() {
        assertEquals("https://api.example.com", OpenAiCompatProvider.normalizeBaseUrl("https://api.example.com"));
    }

    @Test
    void normalize_stripsTrailingV1() {
        assertEquals("https://api.example.com", OpenAiCompatProvider.normalizeBaseUrl("https://api.example.com/v1"));
    }

    @Test
    void normalize_stripsTrailingV1WithSlash() {
        assertEquals("https://api.example.com", OpenAiCompatProvider.normalizeBaseUrl("https://api.example.com/v1/"));
    }

    @Test
    void normalize_stripsTrailingSlashOnly() {
        assertEquals("https://api.deepseek.com", OpenAiCompatProvider.normalizeBaseUrl("https://api.deepseek.com/"));
    }

    @Test
    void normalize_keepsPathWithoutV1Suffix() {
        assertEquals("https://dashscope.aliyuncs.com/compatible-mode",
                OpenAiCompatProvider.normalizeBaseUrl("https://dashscope.aliyuncs.com/compatible-mode"));
    }

    @Test
    void normalize_stripsV1FromSubPath() {
        // one-api / geekai 风格：https://host/api/v1 → 系统再拼 /v1/chat/completions
        assertEquals("https://host/api", OpenAiCompatProvider.normalizeBaseUrl("https://host/api/v1"));
    }

    @Test
    void normalize_handlesNullAndBlank() {
        assertNull(OpenAiCompatProvider.normalizeBaseUrl(null));
        assertNull(OpenAiCompatProvider.normalizeBaseUrl("   "));
    }
}
