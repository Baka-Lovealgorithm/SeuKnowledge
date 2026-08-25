package com.ai.konwledgerepo.common;

import org.springframework.core.task.TaskDecorator;

import java.util.Map;

/**
 * MDC 跨线程传递装饰器：提交异步任务时快照当前线程的 MDC，
 * 工作线程执行前恢复、执行后清理（防线程池复用泄漏到下一任务）。
 * <p>
 * 应用于 {@code applicationTaskExecutor} / {@code visionTaskExecutor}，
 * 使流式问答、文档解析、抽取、识图等 @Async 后台任务的日志同样携带
 * requestId / userId / workspaceId / sessionId。
 */
public class MdcTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable task) {
        Map<String, String> snapshot = LogContext.snapshot();
        return () -> {
            LogContext.restore(snapshot);
            try {
                task.run();
            } finally {
                LogContext.clear();
            }
        };
    }
}
