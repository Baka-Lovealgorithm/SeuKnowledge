package com.ai.konwledgerepo.common;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 当前请求的 SSE 发射器上下文（ThreadLocal）。
 * graph 链路同步执行于同一线程，节点借此判断是否需要流式输出，并记录是否已推送 delta。
 * 位于 common 包：graph 节点与 Web/服务层共用，避免 graph 依赖 service.chat 包。
 * <p>
 * v2 新增 SseFlow：跨线程共享的取消标志与部分答案累积，支持用户主动停止生成。
 */
public final class SseStreamContext {

    private static final ThreadLocal<SseFlow> FLOW = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> DELTA_SENT = ThreadLocal.withInitial(() -> false);

    /**
     * 跨线程共享的流控对象：emitter 引用 + 取消标志 + 已发 delta 累积。
     * 所有通过 ContextPropagator 快照/恢复的线程共享同一实例。
     */
    public static final class SseFlow {
        public final SseEmitter emitter;
        public final AtomicBoolean cancelled = new AtomicBoolean(false);
        private final StringBuilder partial = new StringBuilder();

        public SseFlow(SseEmitter emitter) {
            this.emitter = emitter;
        }

        public synchronized void appendPartial(String text) {
            if (text != null) {
                partial.append(text);
            }
        }

        public synchronized String partialAnswer() {
            return partial.toString();
        }
    }

    public static void set(SseEmitter emitter) {
        FLOW.set(new SseFlow(emitter));
        DELTA_SENT.set(false);
    }

    /** 设置外部创建的 SseFlow（供调用方提前持有引用，如取消端点注册） */
    public static void setFlow(SseFlow flow) {
        if (flow == null) {
            FLOW.remove();
        } else {
            FLOW.set(flow);
        }
        DELTA_SENT.set(false);
    }

    public static SseEmitter get() {
        SseFlow flow = FLOW.get();
        return flow == null ? null : flow.emitter;
    }

    /** 获取当前线程的 SseFlow（注册表用） */
    public static SseFlow getFlow() {
        return FLOW.get();
    }

    /**
     * 获取当前线程的取消标志（可能为 null——无流式上下文时）。
     * 跨线程安全：通过 ContextPropagator 快照传递的 SseFlow 引用，所有并行线程共享同一 AtomicBoolean。
     */
    public static AtomicBoolean cancelFlag() {
        SseFlow flow = FLOW.get();
        return flow == null ? null : flow.cancelled;
    }

    /** 当前线程是否已被请求取消 */
    public static boolean isCancelled() {
        SseFlow flow = FLOW.get();
        return flow != null && flow.cancelled.get();
    }

    /** 请求取消（由取消端点或客户端断开触发） */
    public static void requestCancel() {
        SseFlow flow = FLOW.get();
        if (flow != null) {
            flow.cancelled.set(true);
        }
    }

    /** 如果已取消则抛异常（节点入口检查） */
    public static void throwIfCancelled() {
        if (isCancelled()) {
            SseFlow flow = FLOW.get();
            throw new GenerationCancelledException(flow == null ? "" : flow.partialAnswer());
        }
    }

    /** 已推送的全部 delta 文本（用于停止后部分答案落库） */
    public static String partialAnswer() {
        SseFlow flow = FLOW.get();
        return flow == null ? "" : flow.partialAnswer();
    }

    /**
     * 推送问答阶段状态（节点级），用于前端步骤条展示。
     * 客户端断开等失败时静默忽略，不打断状态图执行。
     */
    public static void sendStage(String stageId, String content) {
        SseEmitter emitter = get();
        if (emitter == null) {
            return;
        }
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("type", "stage");
            payload.put("stage", stageId);
            payload.put("content", content);
            emitter.send(SseEmitter.event().name("message").data(payload));
        } catch (IOException ignored) {
            requestCancel();
        }
    }

    /**
     * 构造统一的 SSE 事件（event 名 message + {type, content} 负载），供各发送方共用，
     * 保证前端按 data.type 分支的载荷结构一致。
     */
    public static SseEmitter.SseEventBuilder event(String type, Object data) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("type", type);
        payload.put("content", data == null ? "" : data);
        return SseEmitter.event().name("message").data(payload);
    }

    /**
     * 推送普通事件（type + content），用于 delta / refs / done 等节点级输出。
     * 客户端断开等失败时静默忽略，不打断流程。
     */
    public static void send(String type, Object data) {
        SseEmitter emitter = get();
        if (emitter == null) {
            return;
        }
        try {
            emitter.send(event(type, data));
            if ("delta".equals(type) && data != null) {
                SseFlow flow = FLOW.get();
                if (flow != null) {
                    flow.appendPartial(String.valueOf(data));
                }
            }
        } catch (IOException ignored) {
            requestCancel();
        }
    }

    /**
     * 显式指定发射器的推送（跨线程安全）：流式模型回调（如 Reactor doOnNext）运行在
     * 供应商/网络线程，ThreadLocal 中的 FLOW 不可见，必须把调用线程捕获的 emitter 传入。
     */
    public static void send(SseEmitter emitter, String type, Object data) {
        if (emitter == null) {
            return;
        }
        try {
            emitter.send(event(type, data));
            if ("delta".equals(type) && data != null) {
                // 跨线程时找不到 ThreadLocal flow，依赖调用方自行累积
            }
        } catch (IOException ignored) {
            requestCancel();
        }
    }

    /** 标记已流式输出过答案 */
    public static void markDeltaSent() {
        DELTA_SENT.set(true);
    }

    public static boolean isDeltaSent() {
        return DELTA_SENT.get();
    }

    public static void clear() {
        FLOW.remove();
        DELTA_SENT.remove();
    }

    /**
     * 快照当前线程的 SSE 上下文（emitter + deltaSent + flow），供并行子线程通过
     * {@link #restore(Object)} 恢复。返回 null 表示当前无上下文。
     * 返回类型为 Object 以保持包边界清晰（调用方无需了解内部结构）。
     */
    public static Object snapshot() {
        SseFlow f = FLOW.get();
        if (f == null && !DELTA_SENT.get()) {
            return null;
        }
        return new Snapshot(f, DELTA_SENT.get());
    }

    /** 恢复快照上下文到当前线程（null 时清理），供并行子线程使用 */
    public static void restore(Object snap) {
        if (snap instanceof Snapshot s) {
            FLOW.set(s.flow());
            DELTA_SENT.set(s.deltaSent());
        } else {
            FLOW.remove();
            DELTA_SENT.remove();
        }
    }

    /** 内部快照 record（避免包外暴露 SseEmitter 类型） */
    private record Snapshot(SseFlow flow, boolean deltaSent) {
    }

    private SseStreamContext() {
    }
}