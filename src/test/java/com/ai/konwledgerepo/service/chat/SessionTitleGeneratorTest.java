package com.ai.konwledgerepo.service.chat;

import com.ai.konwledgerepo.entity.ModelConfig;
import com.ai.konwledgerepo.model.ModelFactory;
import com.ai.konwledgerepo.tracing.QaTracing;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SessionTitleGeneratorTest {

    private static final long WS = 7L;

    private ModelFactory modelFactory;
    private ChatModel chat;
    private SessionTitleGenerator generator;

    @BeforeEach
    void setUp() {
        modelFactory = mock(ModelFactory.class);
        chat = mock(ChatModel.class);
        when(modelFactory.getTitleChatModel(eq(WS))).thenReturn(chat);
        generator = new SessionTitleGenerator(modelFactory, QaTracing.disabled());
    }

    private void stubLlmText(String text) {
        Generation generation = mock(Generation.class);
        AssistantMessage message = mock(AssistantMessage.class);
        when(message.getText()).thenReturn(text);
        when(generation.getOutput()).thenReturn(message);
        ChatResponse response = mock(ChatResponse.class);
        when(response.getResult()).thenReturn(generation);
        when(chat.call(any(Prompt.class))).thenReturn(response);
    }

    @Test
    void generate_usesLlmShortTitle() {
        stubLlmText("Spring Boot 跨域配置");
        assertEquals("Spring Boot 跨域配置", generator.generate("如何在 Spring Boot 中配置跨域请求？", WS));
    }

    @Test
    void generate_truncatesOverlongLlmTitle() {
        stubLlmText("这是一个非常非常非常非常非常非常非常长的标题内容");
        String title = generator.generate("问题", WS);
        assertEquals(SessionTitleGenerator.MAX_TITLE_LEN + 1, title.length());
        assertEquals("…", title.substring(title.length() - 1));
    }

    @Test
    void generate_fallsBackToQuestionWhenLlmFails() {
        when(chat.call(any(Prompt.class))).thenThrow(new RuntimeException("模型不可用"));
        String question = "如何优化数据库查询性能并减少响应时间？";
        assertEquals(SessionTitleGenerator.truncate(question), generator.generate(question, WS));
    }

    @Test
    void generate_blankQuestionReturnsDefault() {
        assertEquals("新会话", generator.generate("   ", WS));
    }

    @Test
    void generate_alwaysCarriesMaxTokensLimit() {
        stubLlmText("标题");
        generator.generate("问题", WS);
        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        verify(chat).call(captor.capture());
        assertEquals(100, captor.getValue().getOptions().getMaxTokens(), "标题输出上限 100 token");
    }

    @Test
    void generate_usesTitleModelNotGenerateUsage() {
        stubLlmText("标题");
        generator.generate("问题", WS);
        verify(modelFactory).getTitleChatModel(WS);
    }

    @Test
    void generate_disableThinkingAppliesExtraBody() {
        // deepseek 配置 disableThinking=true → 标题生成同样关闭思考（复用 JudgeOptions）
        ModelConfig cfg = new ModelConfig();
        cfg.setDisableThinking(true);
        when(modelFactory.resolveTitleChatConfig(eq(WS))).thenReturn(cfg);
        when(chat.getDefaultOptions()).thenReturn(OpenAiChatOptions.builder().model("deepseek-v4-flash").build());
        stubLlmText("标题");
        generator.generate("问题", WS);

        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        verify(chat).call(captor.capture());
        assertTrue(captor.getValue().getOptions() instanceof OpenAiChatOptions, "关闭思考时应构造 OpenAiChatOptions");
        OpenAiChatOptions opts = (OpenAiChatOptions) captor.getValue().getOptions();
        assertEquals(Map.of("thinking", Map.of("type", "disabled")), opts.getExtraBody());
        assertEquals(100, opts.getMaxTokens());
    }

    @Test
    void truncate_takesFirstLineAndCollapsesWhitespace() {
        assertEquals("如何配置数据源", SessionTitleGenerator.truncate("如何配置数据源\n\n第二行是废话"));
    }

    @Test
    void truncate_stripsSurroundingQuotes() {
        assertEquals("配置跨域", SessionTitleGenerator.truncate("“配置跨域”"));
    }

    @Test
    void truncate_blankReturnsDefault() {
        assertEquals("新会话", SessionTitleGenerator.truncate(" \n "));
    }
}
