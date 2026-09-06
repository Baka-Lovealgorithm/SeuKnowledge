package com.ai.konwledgerepo.graph;

import com.ai.konwledgerepo.entity.ModelConfig;
import com.alibaba.cloud.ai.dashscope.api.DashScopeResponseFormat;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.ResponseFormat;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * JudgeOptions 选项构造测试：覆盖 jsonMode 开/关、DashScope/OpenAI 兼容 provider 双路、router 路径。
 */
class JudgeOptionsTest {

    private static ChatModel dashScopeMock() {
        ChatModel m = mock(ChatModel.class);
        when(m.getDefaultOptions()).thenReturn(DashScopeChatOptions.builder().model("qwen-turbo").build());
        return m;
    }

    private static ChatModel openAiMock() {
        ChatModel m = mock(ChatModel.class);
        when(m.getDefaultOptions()).thenReturn(OpenAiChatOptions.builder().model("deepseek-v4-flash").build());
        return m;
    }

    private static ChatModel unknownMock() {
        ChatModel m = mock(ChatModel.class);
        when(m.getDefaultOptions()).thenReturn(null); // 未知 provider
        return m;
    }

    // ===== jsonMode=false（judge 家族统一温度 0）=====

    @Test
    void jsonModeDisabled_maxTokensOnly() {
        ChatOptions opts = JudgeOptions.of(dashScopeMock(), null, 1000);
        assertNotNull(opts.getMaxTokens());
        assertEquals(1000, (int) opts.getMaxTokens());
        // 无 responseFormat（DashScope 路径）
        assertTrue(opts instanceof ChatOptions);
    }

    /** 核心 pin：非 jsonMode（自检打分/标题/摘要默认路径）也必须温度 0，打分去随机 */
    @Test
    void jsonModeDisabled_temperatureZero_generic() {
        ChatOptions opts = JudgeOptions.of(unknownMock(), null, 1000);
        assertEquals(1000, (int) opts.getMaxTokens());
        assertEquals(0.0, opts.getTemperature(), 0.001);
    }

    /** OpenAI 兼容 + 关思考：extraBody 保留，温度同步归 0 */
    @Test
    void jsonModeDisabled_openAiWithDisableThinking_tempZeroAndExtraBody() {
        ModelConfig cfg = new ModelConfig();
        cfg.setDisableThinking(true);
        ChatOptions opts = JudgeOptions.of(openAiMock(), cfg, 1000);
        assertTrue(opts instanceof OpenAiChatOptions);
        OpenAiChatOptions o = (OpenAiChatOptions) opts;
        assertEquals(0.0, o.getTemperature(), 0.001);
        assertEquals(1000, (int) o.getMaxTokens());
        assertEquals(Map.of("thinking", Map.of("type", "disabled")), o.getExtraBody());
    }

    /** DashScope + 关思考：非 jsonMode 分支同样生效 enableThinking(false)（对称性补齐的回归 pin） */
    @Test
    void jsonModeDisabled_dashScopeWithDisableThinking_thinkingOff() {
        ModelConfig cfg = new ModelConfig();
        cfg.setDisableThinking(true);
        ChatOptions opts = JudgeOptions.of(dashScopeMock(), cfg, 1000);
        assertTrue(opts instanceof DashScopeChatOptions);
        DashScopeChatOptions d = (DashScopeChatOptions) opts;
        assertEquals(Boolean.FALSE, d.getEnableThinking());
        assertEquals(0.0, d.getTemperature(), 0.001);
        assertEquals(1000, (int) d.getMaxTokens());
    }

    // ===== jsonMode=true DashScope =====

    @Test
    void jsonModeEnabled_dashScope_hasResponseFormat() {
        ChatOptions opts = JudgeOptions.of(dashScopeMock(), null, 1000, true);
        assertTrue(opts instanceof DashScopeChatOptions);
        DashScopeChatOptions d = (DashScopeChatOptions) opts;
        assertNotNull(d.getResponseFormat());
        assertEquals(DashScopeResponseFormat.Type.JSON_OBJECT, d.getResponseFormat().getType());
        assertEquals(0.0, d.getTemperature(), 0.001);
        assertEquals(1000, (int) d.getMaxTokens());
    }

    // ===== jsonMode=true OpenAI =====

    @Test
    void jsonModeEnabled_openAi_hasResponseFormat() {
        ChatOptions opts = JudgeOptions.of(openAiMock(), null, 1000, true);
        assertTrue(opts instanceof OpenAiChatOptions);
        OpenAiChatOptions o = (OpenAiChatOptions) opts;
        assertNotNull(o.getResponseFormat());
        assertEquals(ResponseFormat.Type.JSON_OBJECT, o.getResponseFormat().getType());
        assertEquals(0.0, o.getTemperature(), 0.001);
        assertEquals(1000, (int) o.getMaxTokens());
    }

    // ===== jsonMode=true 未知 provider =====

    @Test
    void jsonModeEnabled_unknownProvider_fallsBackToGeneric() {
        ChatOptions opts = JudgeOptions.of(unknownMock(), null, 1000, true);
        assertNotNull(opts.getMaxTokens());
        assertEquals(1000, (int) opts.getMaxTokens());
        assertEquals(0.0, opts.getTemperature(), 0.001);
    }

    // ===== router() 路径 =====

    @Test
    void router_dashScope_hasTemperatureZeroAndMaxTokens32() {
        ChatOptions opts = JudgeOptions.router(dashScopeMock(), null);
        assertTrue(opts instanceof DashScopeChatOptions);
        DashScopeChatOptions d = (DashScopeChatOptions) opts;
        assertEquals(0.0, d.getTemperature(), 0.001);
        assertEquals(32, (int) d.getMaxTokens());
        // 路由不应带 responseFormat
        assertNull(d.getResponseFormat());
    }

    @Test
    void router_openAi_hasTemperatureZeroAndMaxTokens32() {
        ChatOptions opts = JudgeOptions.router(openAiMock(), null);
        assertTrue(opts instanceof OpenAiChatOptions);
        OpenAiChatOptions o = (OpenAiChatOptions) opts;
        assertEquals(0.0, o.getTemperature(), 0.001);
        assertEquals(32, (int) o.getMaxTokens());
        assertNull(o.getResponseFormat());
    }

    @Test
    void router_unknownProvider_generic() {
        ChatOptions opts = JudgeOptions.router(unknownMock(), null);
        assertEquals(0.0, opts.getTemperature(), 0.001);
        assertEquals(32, (int) opts.getMaxTokens());
    }
}