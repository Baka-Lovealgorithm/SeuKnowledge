package com.ai.konwledgerepo.tracing;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingResponse;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LLM 调用可视化测试：generation span 上应写入输入（gen_ai.prompt）与输出（gen_ai.completion），
 * 供 Langfuse 展示「这次调用了什么、模型回了什么」；向量调用只写输入不写输出；超长截断。
 */
class LlmTraceGenAiIoTest {

    private Tracer tracer;
    private SpanBuilder spanBuilder;
    private Span span;
    private Scope scope;
    private QaTracing tracing;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        tracer = mock(Tracer.class);
        spanBuilder = mock(SpanBuilder.class);
        span = mock(Span.class);
        scope = mock(Scope.class);
        when(tracer.spanBuilder(anyString())).thenReturn(spanBuilder);
        when(spanBuilder.startSpan()).thenReturn(span);
        when(span.makeCurrent()).thenReturn(scope);
        tracing = new QaTracing(tracer, true);
    }

    @Test
    void callWithMessages_writesPromptAndCompletion() {
        ChatModel chat = mock(ChatModel.class);
        when(chat.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("报销需填表")))));
        List<Message> messages = List.of(
                new SystemMessage("你是助手"),
                new UserMessage("如何报销？"));

        LlmTrace.call(tracing, chat, messages);

        verify(span, atLeastOnce()).setAttribute(eq("gen_ai.prompt"), org.mockito.ArgumentMatchers.contains("如何报销？"));
        verify(span, atLeastOnce()).setAttribute(eq("gen_ai.completion"), eq("报销需填表"));
    }

    @Test
    void streamWritesPromptAndCompletion() {
        ChatModel chat = mock(ChatModel.class);
        when(chat.stream(any(Prompt.class))).thenReturn(Flux.just(
                new ChatResponse(List.of(new Generation(new AssistantMessage("你好"))))));
        List<Message> messages = List.of(new UserMessage("打招呼"));

        LlmTrace.stream(tracing, chat, messages, null);

        verify(span, atLeastOnce()).setAttribute(eq("gen_ai.prompt"), org.mockito.ArgumentMatchers.contains("打招呼"));
        verify(span, atLeastOnce()).setAttribute(eq("gen_ai.completion"), eq("你好"));
    }

    @Test
    void visionWritesPromptAndCompletion() {
        ChatModel chat = mock(ChatModel.class);
        when(chat.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("这是表格")))));
        Media media = Media.builder()
                .mimeType(org.springframework.util.MimeTypeUtils.IMAGE_PNG)
                .data("fake".getBytes())
                .build();

        LlmTrace.vision(tracing, chat, "请转写页面", media);

        verify(span, atLeastOnce()).setAttribute(eq("gen_ai.prompt"), eq("请转写页面"));
        verify(span, atLeastOnce()).setAttribute(eq("gen_ai.completion"), eq("这是表格"));
    }

    @Test
    void embedWritesPromptOnly() {
        EmbeddingModel model = mock(EmbeddingModel.class);
        Embedding embedding = mock(Embedding.class);
        when(embedding.getOutput()).thenReturn(new float[]{1.0f, 2.0f});
        when(model.embedForResponse(any())).thenReturn(new EmbeddingResponse(List.of(embedding)));

        LlmTrace.embed(tracing, model, "查询文本");

        verify(span, atLeastOnce()).setAttribute(eq("gen_ai.prompt"), eq("查询文本"));
        // 向量输出不写 completion
        verify(span, org.mockito.Mockito.never())
                .setAttribute(eq("gen_ai.completion"), any(String.class));
    }

    @Test
    void longPrompt_isTruncated() {
        ChatModel chat = mock(ChatModel.class);
        when(chat.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("ok")))));
        String longText = "字".repeat(20000);

        LlmTrace.call(tracing, chat, longText);

        // 截断到 16000 字符 + 省略号
        verify(span, atLeastOnce()).setAttribute(
                eq("gen_ai.prompt"),
                org.mockito.ArgumentMatchers.argThat(s -> s.length() <= 16001 && s.endsWith("…")));
        assertTrue(longText.length() > 16000);
    }
}
