package com.ai.konwledgerepo.config;

import com.ai.konwledgerepo.config.props.SeuQaProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * QA 链路并行执行器配置：虚拟线程 per-task 执行器，供节点内并行检索/自检使用。
 * 与 {@link AsyncConfig} 的平台线程池（@Async 文档解析/抽取/流式问答）独立。
 * <p>
 * 虚拟线程阻塞时自动让出载体线程，避免线程池耗尽；per-task 无池化复用，ThreadLocal
 * 上下文由 {@link com.ai.konwledgerepo.common.ContextPropagator} 显式传播，无泄漏风险。
 * <p>
 * 并行开关 {@code seuknowledge.qa.parallel}（默认 true）仅控制节点内并行是否启用；
 * 关闭后各节点走原串行路径，执行器仍可用（便于 A/B 对比）。
 */
@Configuration
@ConditionalOnProperty(value = "seuknowledge.qa.parallel", havingValue = "true", matchIfMissing = true)
public class QaParallelConfig {

    @Bean("qaTaskExecutor")
    public ExecutorService qaTaskExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}