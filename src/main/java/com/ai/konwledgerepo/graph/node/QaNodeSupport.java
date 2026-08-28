package com.ai.konwledgerepo.graph.node;

import com.ai.konwledgerepo.common.SseStreamContext;
import com.ai.konwledgerepo.tracing.QaTracing;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import io.opentelemetry.api.trace.Span;

import java.util.Map;

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
}