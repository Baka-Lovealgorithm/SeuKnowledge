package com.ai.konwledgerepo.common;

import com.ai.konwledgerepo.tracing.TokenAccumulator;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLongArray;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 跨线程上下文传播工具测试：
 * 并行子线程内可见 MDC / SSE emitter / Token 累加器 / OTel Context；执行后清理。
 */
class ContextPropagatorTest {

    private static final ExecutorService EXEC = Executors.newVirtualThreadPerTaskExecutor();

    @Test
    void wrap_callable_subthreadSeesTokenAccumulator() throws Exception {
        TokenAccumulator.begin();
        try {
            Map<String, AtomicLongArray> snap = TokenAccumulator.snapshot();
            // 子线程通过 wrapCallable 恢复累加器并累加，主线程 totals() 应能看到
            long[] totals = CompletableFuture.supplyAsync(
                    ContextPropagator.wrapSupplier(() -> {
                        TokenAccumulator.accumulate(TokenAccumulator.TYPE_TEXT, usage(10, 20, 30));
                        return TokenAccumulator.totals();
                    }), EXEC).get();
            assertEquals(10, totals[0]);
            assertEquals(20, totals[1]);
            assertEquals(30, totals[2]);
            assertSame(snap, TokenAccumulator.snapshot(), "主线程累加器 map 引用不变（共享）");
            // 子线程执行后不应污染主线程：主线程仍持有自己的累加器
            assertTrue(TokenAccumulator.snapshot() != null);
        } finally {
            TokenAccumulator.clear();
        }
    }

    @Test
    void wrap_callable_subthreadSeesSseEmitter() throws Exception {
        SseEmitter emitter = new SseEmitter();
        SseStreamContext.setFlow(new SseStreamContext.SseFlow(emitter));
        try {
            AtomicBoolean seen = new AtomicBoolean(false);
            CompletableFuture.runAsync(ContextPropagator.wrap(() -> {
                SseEmitter e = SseStreamContext.get();
                seen.set(e != null && e == emitter);
            }), EXEC).get(5, TimeUnit.SECONDS);
            assertTrue(seen.get(), "子线程应能读到主线程设置的 SseEmitter");
        } finally {
            SseStreamContext.clear();
        }
    }

    @Test
    void subthreadMarkDeltaSent_isVisibleInMainThread() throws Exception {
        // 模拟真实链路：ChatStreamService 在请求线程创建共享 SseFlow，
        // 图在虚拟线程执行时 markDeltaSent，请求线程随后 isDeltaSent 必须为 true
        // （回归：曾因 DELTA_SENT 存 ThreadLocal 导致跨线程丢失，整段答案被重复补发）
        SseEmitter emitter = new SseEmitter();
        SseStreamContext.SseFlow flow = new SseStreamContext.SseFlow(emitter);
        SseStreamContext.setFlow(flow);
        try {
            assertTrue(!SseStreamContext.isDeltaSent(), "初始未推送 delta");
            CompletableFuture.runAsync(ContextPropagator.wrap(() ->
                    SseStreamContext.markDeltaSent()), EXEC).get(5, TimeUnit.SECONDS);
            assertTrue(SseStreamContext.isDeltaSent(), "子线程 markDeltaSent 后主线程应可见（SseFlow 共享标记）");
        } finally {
            SseStreamContext.clear();
        }
    }

    @Test
    void sameThreadMarkDeltaSent_withoutFlow_fallsBackToThreadLocal() {
        // 无 SseFlow（非流式场景）时，markDeltaSent/isDeltaSent 回退 ThreadLocal，行为不变
        SseStreamContext.setFlow(null);
        try {
            assertTrue(!SseStreamContext.isDeltaSent());
            SseStreamContext.markDeltaSent();
            assertTrue(SseStreamContext.isDeltaSent());
        } finally {
            SseStreamContext.clear();
        }
    }

    @Test
    void wrap_callable_subthreadSeesMdcAndCleansUp() throws Exception {
        ExecutorService single = Executors.newSingleThreadExecutor();
        try {
            // 1. 主线程有上下文 → 子线程应能读到
            LogContext.put("requestId", "req-123");
            AtomicBoolean mdcSeen = new AtomicBoolean(false);
            single.submit(ContextPropagator.wrap(() -> mdcSeen.set(
                    "req-123".equals(LogContext.snapshot().get("requestId"))))).get(5, TimeUnit.SECONDS);
            assertTrue(mdcSeen.get(), "子线程应能读到主线程设置的 MDC requestId");

            // 2. 主线程上下文已清空后再提交 → 子线程不应看到任何 requestId（无跨任务残留）
            LogContext.clear();
            AtomicBoolean leaked = new AtomicBoolean(false);
            single.submit(ContextPropagator.wrap(() -> leaked.set(
                    LogContext.snapshot() != null && LogContext.snapshot().containsKey("requestId")))).get(5, TimeUnit.SECONDS);
            assertTrue(!leaked.get(), "主线程无上下文时，子线程不应残留上一任务 MDC");
        } finally {
            single.shutdown();
            LogContext.clear();
        }
    }

    @Test
    void wrap_withoutAnyContext_isSafe() throws Exception {
        // 无 MDC / SSE / Token 累加器时包装执行不应抛异常
        CompletableFuture<Boolean> done = CompletableFuture.supplyAsync(
                ContextPropagator.wrapSupplier(() -> true), EXEC);
        assertEquals(Boolean.TRUE, done.get());
        assertNull(TokenAccumulator.snapshot(), "执行后不应产生累加器残留");
    }

    private static Usage usage(long in, long out, long total) {
        return new Usage() {
            @Override
            public Integer getPromptTokens() {
                return (int) in;
            }

            @Override
            public Integer getCompletionTokens() {
                return (int) out;
            }

            @Override
            public Integer getTotalTokens() {
                return (int) total;
            }

            @Override
            public Object getNativeUsage() {
                return null;
            }
        };
    }
}