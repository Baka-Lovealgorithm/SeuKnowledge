package com.ai.konwledgerepo.common;

import org.slf4j.MDC;

import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 日志上下文（SLF4J MDC）键与辅助。
 * <p>
 * 每个 HTTP 请求由 {@link com.ai.konwledgerepo.security.AuthInterceptor} 注入
 * requestId / userId / workspaceId，{@code ChatController} 补充 sessionId；
 * 异步任务（流式问答/文档解析/抽取/识图）由 {@link MdcTaskDecorator} 将快照复制到工作线程。
 * Logback pattern 按 {@code %X{requestId:-}} 等输出（无值时显示 -），
 * 一次问答的全部日志可用 requestId 一次 grep 串起，与 Langfuse trace 相互印证。
 */
public final class LogContext {

    public static final String REQUEST_ID = "requestId";
    public static final String USER_ID = "userId";
    public static final String WORKSPACE_ID = "workspaceId";
    public static final String SESSION_ID = "sessionId";

    private LogContext() {
    }

    /** 生成/透传 requestId 并写入 MDC（透传头优先，缺失时生成 UUID，便于与网关/前端对齐） */
    public static void initRequestId(String headerValue) {
        String id = (headerValue == null || headerValue.isBlank())
                ? UUID.randomUUID().toString().replace("-", "")
                : headerValue.trim();
        MDC.put(REQUEST_ID, id);
    }

    /** 写入 userId / workspaceId（null 则移除对应键，如无工作空间的用户） */
    public static void setUserContext(Long userId, Long workspaceId) {
        put(USER_ID, userId);
        put(WORKSPACE_ID, workspaceId);
    }

    /** 写入/移除任意上下文键 */
    public static void put(String key, Object value) {
        if (value == null) {
            MDC.remove(key);
        } else {
            MDC.put(key, String.valueOf(value));
        }
    }

    /** 在 sessionId 上下文中执行并返回结果（执行完移除，避免请求线程复用污染） */
    public static <T> T withSession(Long sessionId, Supplier<T> action) {
        put(SESSION_ID, sessionId);
        try {
            return action.get();
        } finally {
            MDC.remove(SESSION_ID);
        }
    }

    /** 清理全部 MDC（请求结束 / 异步任务执行完毕） */
    public static void clear() {
        MDC.clear();
    }

    /** 当前 MDC 快照（供异步线程复制；null 表示当前无上下文） */
    public static Map<String, String> snapshot() {
        return MDC.getCopyOfContextMap();
    }

    /** 以快照恢复 MDC（异步线程执行前调用） */
    public static void restore(Map<String, String> snapshot) {
        if (snapshot != null) {
            MDC.setContextMap(snapshot);
        }
    }
}
