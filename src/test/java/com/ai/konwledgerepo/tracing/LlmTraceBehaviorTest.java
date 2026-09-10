package com.ai.konwledgerepo.tracing;

import com.ai.konwledgerepo.common.GenerationCancelledException;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LlmTrace 行为契约测试（重构安全网）：<b>不涉及任何重构意图</b>，只钉住四类可观察行为——
 * <ol>
 *   <li><b>异常分类</b>：超时 → {@link LlmTimeoutException}、取消 → {@link GenerationCancelledException}，
 *       且分别落 {@code llm.timeout} / {@code llm.cancelled} 属性（Langfuse 据此区分失败原因）；</li>
 *   <li><b>usage 累加</b>：每次调用按 text / vision / embedding 类别累加到 {@link TokenAccumulator}；</li>
 *   <li><b>异常传播</b>：原始 RuntimeException 不被包装（调用方按具体类型 catch）；</li>
 *   <li><b>span 生命周期</b>：正常与异常路径都必须 {@code span.end()}（否则 trace 泄漏）。</li>
 * </ol>
 * 这些契约在 LlmTrace 抽出公共骨架时最容易被无意改变，故先建网再重构。
 */
class LlmTraceBehaviorTest {

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
        TokenAccumulator.begin();
    }

    @AfterEach
    void tearDown() {
        TokenAccumulator.clear();
    }

    // ===== 1. 异常分类 =====

    @Test
    void call_cancelledBeforeInvocation_throwsWithoutTouchingModelOrSpan() {
        ChatModel chat = mock(ChatModel.class);

        GenerationCancelledException e = assertThrows(GenerationCancelledException.class,
                () -> LlmTrace.call(tracing, chat, List.of(new UserMessage("q")), null, () -> true));

        assertEquals("", e.getPartial(), "调用前取消无已生成内容");
        // 调用前取消在 beginGeneration 之前短路：不该为不执行的工作建 span，也不该打模型
        verify(chat, never()).call(any(Prompt.class));
        verify(tracer, never()).spanBuilder(anyString());
    }

    @Test
    void stream_cancelledMidStream_marksCancelledAndCarriesPartial() {
        ChatModel chat = mock(ChatModel.class);
        when(chat.stream(any(Prompt.class))).thenReturn(Flux.just(
                response("第一段", null),
                response("第二段", null)));
        // 取消位在首个 token 的 onDelta 回调中置位；LlmTrace 在下一个 token 上就会检查到并中断
        AtomicBoolean cancelled = new AtomicBoolean(false);

        GenerationCancelledException e = assertThrows(GenerationCancelledException.class,
                () -> LlmTrace.stream(tracing, chat, List.of(new UserMessage("q")),
                        delta -> cancelled.set(true), cancelled::get));

        assertTrue(e.getPartial().contains("第一段"),
                "流式取消应携带已生成片段，实际=" + e.getPartial());
        verify(span).setAttribute("llm.cancelled", true);
        verify(span).end();
    }

    @Test
    void call_runtimeException_propagatesUnwrappedAndEndsSpan() {
        ChatModel chat = mock(ChatModel.class);
        IllegalStateException boom = new IllegalStateException("模型不可用");
        when(chat.call(any(Prompt.class))).thenThrow(boom);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> LlmTrace.call(tracing, chat, List.of(new UserMessage("q"))));

        assertEquals("模型不可用", e.getMessage(), "原始异常类型与消息不得被包装");
        verify(span).recordException(boom);
        verify(span).end();
    }

    // ===== 2. usage 累加 =====

    @Test
    void call_accumulatesTextTokens() {
        ChatModel chat = mock(ChatModel.class);
        when(chat.call(any(Prompt.class))).thenReturn(response("答", usage(10, 20, 30)));

        LlmTrace.call(tracing, chat, List.of(new UserMessage("q")));

        assertArrayEquals(new long[]{10, 20, 30}, TokenAccumulator.totals(),
                "文本调用应按 text 类别累加 token");
    }

    @Test
    void stream_accumulatesLastUsageOnly() {
        ChatModel chat = mock(ChatModel.class);
        when(chat.stream(any(Prompt.class))).thenReturn(Flux.just(
                response("a", null),
                response("b", usage(5, 7, 12))));

        LlmTrace.stream(tracing, chat, List.of(new UserMessage("q")), delta -> { });

        assertArrayEquals(new long[]{5, 7, 12}, TokenAccumulator.totals(),
                "流式调用只取最后一条携带 usage 的响应，不逐条累加");
    }

    @Test
    void vision_accumulatesVisionTokens() {
        ChatModel chat = mock(ChatModel.class);
        when(chat.call(any(Prompt.class))).thenReturn(response("描述", usage(3, 4, 7)));

        LlmTrace.vision(tracing, chat, "看图", org.springframework.ai.content.Media.builder()
                .mimeType(org.springframework.util.MimeTypeUtils.IMAGE_PNG)
                .data(new byte[]{1, 2, 3})
                .build());

        assertArrayEquals(new long[]{3, 4, 7}, TokenAccumulator.totals(),
                "识图调用应按 vision 类别累加，与文本类别隔离");
    }

    // ===== 3. span 生命周期 =====

    @Test
    void embed_endsSpanOnSuccess() {
        org.springframework.ai.embedding.EmbeddingModel model = mock(org.springframework.ai.embedding.EmbeddingModel.class);
        org.springframework.ai.embedding.Embedding embedding = mock(org.springframework.ai.embedding.Embedding.class);
        when(embedding.getOutput()).thenReturn(new float[]{0.1f, 0.2f});
        when(model.embedForResponse(any()))
                .thenReturn(new org.springframework.ai.embedding.EmbeddingResponse(List.of(embedding)));

        LlmTrace.embed(tracing, model, "查询文本");

        verify(span).end();
    }

    // ===== 4. withTimeout 契约 =====

    @Test
    void withTimeout_interruptedThread_throwsTimeoutExceptionAndRestoresFlag() {
        // 任务阻塞，等待被打断
        assertThrows(LlmTimeoutException.class, () -> {
            Thread.currentThread().interrupt();
            try {
                LlmTrace.withTimeout("probe", () -> {
                    Thread.sleep(10_000);
                    return null;
                });
            } finally {
                Thread.interrupted(); // 清理中断位，避免污染后续用例
            }
        });
    }

    // ===== 辅助 =====

    private static ChatResponse response(String text, Usage usage) {
        ChatResponseMetadata metadata = usage == null
                ? ChatResponseMetadata.builder().build()
                : ChatResponseMetadata.builder().usage(usage).build();
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))), metadata);
    }

    private static Usage usage(int in, int out, int total) {
        return new DefaultUsage(in, out, total);
    }
}
