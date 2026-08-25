package com.ai.konwledgerepo.common;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * 当前请求的 SSE 发射器上下文（ThreadLocal）。
 * graph 链路同步执行于同一线程，节点借此判断是否需要流式输出，并记录是否已推送 delta。
 * 位于 common 包：graph 节点与 Web/服务层共用，避免 graph 依赖 service.chat 包。
 */
public final class SseStreamContext {

    private static final ThreadLocal<SseEmitter> EMITTER = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> DELTA_SENT = ThreadLocal.withInitial(() -> false);

    public static void set(SseEmitter emitter) {
        EMITTER.set(emitter);
        DELTA_SENT.set(false);
    }

    public static SseEmitter get() {
        return EMITTER.get();
    }

    /**
     * 推送问答阶段状态（节点级），用于前端步骤条展示。
     * 客户端断开等失败时静默忽略，不打断状态图执行。
     *
     * @param stageId 节点标识（INTENT_ROUTE / QUERY_REWRITE / KNOWLEDGE_RECALL / RERANK /
     *                ANSWER_COMPOSE / ANSWER_VERIFY / RETRY_FALLBACK / CHAT_ONLY）
     * @param content 展示文案（可含重试轮次、多源召回统计等细节）
     */
    public static void sendStage(String stageId, String content) {
        SseEmitter emitter = EMITTER.get();
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
            // 客户端已断开：不阻断问答流程
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
        SseEmitter emitter = EMITTER.get();
        if (emitter == null) {
            return;
        }
        try {
            emitter.send(event(type, data));
        } catch (IOException ignored) {
            // 客户端已断开：不阻断问答流程
        }
    }

    /**
     * 显式指定发射器的推送（跨线程安全）：流式模型回调（如 Reactor doOnNext）运行在
     * 供应商/网络线程，ThreadLocal 中的 EMITTER 不可见，必须把调用线程捕获的 emitter 传入。
     */
    public static void send(SseEmitter emitter, String type, Object data) {
        if (emitter == null) {
            return;
        }
        try {
            emitter.send(event(type, data));
        } catch (IOException ignored) {
            // 客户端已断开：不阻断问答流程
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
        EMITTER.remove();
        DELTA_SENT.remove();
    }

    /**
     * 快照当前线程的 SSE 上下文（emitter + deltaSent），供并行子线程通过
     * {@link #restore(Object)} 恢复。返回 null 表示当前无上下文。
     * 返回类型为 Object 以保持包边界清晰（调用方无需了解内部结构）。
     */
    public static Object snapshot() {
        SseEmitter e = EMITTER.get();
        if (e == null && !DELTA_SENT.get()) {
            return null;
        }
        return new Snapshot(e, DELTA_SENT.get());
    }

    /** 恢复快照上下文到当前线程（null 时清理），供并行子线程使用 */
    public static void restore(Object snap) {
        if (snap instanceof Snapshot s) {
            EMITTER.set(s.emitter());
            DELTA_SENT.set(s.deltaSent());
        } else {
            EMITTER.remove();
            DELTA_SENT.remove();
        }
    }

    /** 内部快照 record（避免包外暴露 SseEmitter 类型） */
    private record Snapshot(SseEmitter emitter, boolean deltaSent) {
    }

    private SseStreamContext() {
    }
}
