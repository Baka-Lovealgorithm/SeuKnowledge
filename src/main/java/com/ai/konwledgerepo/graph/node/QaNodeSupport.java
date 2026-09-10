package com.ai.konwledgerepo.graph.node;

import com.ai.konwledgerepo.common.SseStreamContext;
import com.ai.konwledgerepo.tracing.QaTracing;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import io.opentelemetry.api.trace.Span;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 * 问答图节点基类：统一管理取消检查、OTel span 生命周期与异常记录。
 * 子类实现 {@link #applyInternal(OverAllState)} 和 {@link #spanName()}。
 */
public abstract class QaNodeSupport implements NodeAction {

    protected final QaTracing qaTracing;

    protected QaNodeSupport(QaTracing qaTracing) {
        this.qaTracing = qaTracing;
    }

    @Override
    public final Map<String, Object> apply(OverAllState state) throws Exception {
        SseStreamContext.throwIfCancelled();
        Span span = qaTracing.begin(spanName());
        try {
            return applyInternal(state, span);
        } catch (Exception e) {
            span.recordException(e);
            throw e;
        } finally {
            span.end();
        }
    }

    /** 子类实现业务逻辑（sendStage 等由子类自行调用；span 供设置属性） */
    protected abstract Map<String, Object> applyInternal(OverAllState state, Span span) throws Exception;

    /** 子类返回 OTel span 名称，与现有 "node/xxx" 保持一致 */
    protected abstract String spanName();

    /**
     * 一次取齐流式调用所需的全部上下文，替代各节点重复的
     * {@code get() / getFlow() / cancelFlag()} 三连。
     * <p>
     * {@link #streamable()} 为 false 时（SSE 未建立，即非流式请求）调用方应走
     * {@code LlmTrace.call}；为 true 时走 {@code LlmTrace.stream} 并在结束后调
     * {@link SseStreamContext#markDeltaSent()}——是否已发送 delta 会写进 span，
     * 漏调会让追踪记录与实际输出不符。这些步骤集中在 {@link StreamContext#callOrStream} 里，
     * 调用方不必再关心。
     */
    protected StreamContext streamContext() {
        return new StreamContext(SseStreamContext.get(), SseStreamContext.getFlow(), SseStreamContext.cancelFlag());
    }

    /** 节点流式调用的上下文快照（emitter / flow / 取消标志三者同时取，避免时序错位） */
    protected record StreamContext(SseEmitter emitter, SseStreamContext.SseFlow flow, AtomicBoolean cancelled) {

        /** SSE 已建立，本次作答可以流式推送 delta */
        boolean streamable() {
            return emitter != null;
        }

        /** 取消标志 → LlmTrace 需要的 BooleanSupplier（无取消标志时为 null） */
        BooleanSupplier cancelSupplier() {
            return cancelled == null ? null : cancelled::get;
        }

        /**
         * 统一发送 delta 并标记已发。
         * 仅在 {@link #streamable()} 为 true 时调用。
         */
        void sendDelta(String text) {
            SseStreamContext.send(flow, emitter, "delta", text);
        }

        /** 收尾：无论流式与否都需产出「已发送 delta」标记，供追踪与前端判定 */
        void markStreamed() {
            SseStreamContext.markDeltaSent();
        }
    }
}
