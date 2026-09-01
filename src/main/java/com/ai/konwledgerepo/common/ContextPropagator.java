package com.ai.konwledgerepo.common;

import com.ai.konwledgerepo.tracing.TokenAccumulator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;

import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.function.Supplier;

/**
 * 跨线程上下文传播工具：并行子线程提交前快照主线程的 MDC / SSE / Token / OTel 上下文，
 * 执行前恢复、执行后清理，保证并行任务内生成的日志、trace span、token 统计与主线程一致。
 * <p>
 * 用法：{@code CompletableFuture.supplyAsync(ContextPropagator.wrapCallable(() -> ...), executor)}
 * 或 {@code executor.execute(ContextPropagator.wrap(() -> ...))}。
 * <p>
 * 设计参考 {@link MdcTaskDecorator}，但扩展了 SseStreamContext、TokenAccumulator 与
 * OTel Context，且为静态工具（无需 bean 注入），适配虚拟线程 per-task 执行器。
 */
public final class ContextPropagator {

    private ContextPropagator() {
    }

    /** 包装 Runnable：快照 → 执行时恢复 → 执行后清理 */
    public static Runnable wrap(Runnable task) {
        Map<String, String> mdc = LogContext.snapshot();
        Object sse = SseStreamContext.snapshot();
        Map<String, AtomicLongArray> tokens = TokenAccumulator.snapshot();
        Context otel = Context.current();
        return () -> {
            LogContext.restore(mdc);
            SseStreamContext.restore(sse);
            TokenAccumulator.restore(tokens);
            try (Scope scope = otel.makeCurrent()) {
                task.run();
            } finally {
                LogContext.clear();
                SseStreamContext.clear();
                TokenAccumulator.clear();
            }
        };
    }

    /** 包装 Callable：快照 → 执行时恢复 → 执行后清理 */
    public static <T> Callable<T> wrapCallable(Callable<T> task) {
        return wrap(task);
    }

    /** 包装 Supplier（供 CompletableFuture.supplyAsync 使用） */
    public static <T> Supplier<T> wrapSupplier(Supplier<T> task) {
        Map<String, String> mdc = LogContext.snapshot();
        Object sse = SseStreamContext.snapshot();
        Map<String, AtomicLongArray> tokens = TokenAccumulator.snapshot();
        Context otel = Context.current();
        return () -> {
            LogContext.restore(mdc);
            SseStreamContext.restore(sse);
            TokenAccumulator.restore(tokens);
            try (Scope scope = otel.makeCurrent()) {
                return task.get();
            } finally {
                LogContext.clear();
                SseStreamContext.clear();
                TokenAccumulator.clear();
            }
        };
    }

    private static <T> Callable<T> wrap(Callable<T> task) {
        Map<String, String> mdc = LogContext.snapshot();
        Object sse = SseStreamContext.snapshot();
        Map<String, AtomicLongArray> tokens = TokenAccumulator.snapshot();
        Context otel = Context.current();
        return () -> {
            LogContext.restore(mdc);
            SseStreamContext.restore(sse);
            TokenAccumulator.restore(tokens);
            try (Scope scope = otel.makeCurrent()) {
                return task.call();
            } finally {
                LogContext.clear();
                SseStreamContext.clear();
                TokenAccumulator.clear();
            }
        };
    }
}