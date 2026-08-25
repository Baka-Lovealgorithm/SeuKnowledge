package com.ai.konwledgerepo.config;

import com.ai.konwledgerepo.common.MdcTaskDecorator;
import com.ai.konwledgerepo.config.props.SeuAsyncProperties;
import com.ai.konwledgerepo.config.props.SeuDocumentProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 任务线程池配置。
 * <p>
 * applicationTaskExecutor：通用 @Async 执行池。本类中定义了 Executor 类型 bean（visionTaskExecutor），
 * 会触发 Spring Boot {@code TaskExecutionAutoConfiguration} 的 {@code @ConditionalOnMissingBean(Executor.class)}
 * 条件失效（整个自动配置被抑制、默认池消失），因此这里显式补回，并同时注册 Boot 标准别名
 * {@code taskExecutor}（对应 AsyncAnnotationBeanPostProcessor.DEFAULT_TASK_EXECUTOR_BEAN_NAME），
 * 使不带限定符的 {@code @Async} 能解析到本池。
 * <p>
 * 注意：若只注册 {@code applicationTaskExecutor} 而缺少 {@code taskExecutor} 别名，无限定符
 * {@code @Async} 会因两个 TaskExecutor bean 类型歧义且无名匹配 bean 而回退到
 * {@code SimpleAsyncTaskExecutor}（每任务一线程、无上限、无拒绝策略）——已确认为此前线上缺陷，
 * 该别名不可删除。池容量可经 {@code seuknowledge.async.*} 调优（默认 8/32/256）。
 * <p>
 * visionTaskExecutor：PDF 识图专用有界线程池（仅 LLM 网络调用并行，页面渲染仍在主线程）。
 * 并行度由 {@code seuknowledge.document.vision-parallel} 控制（默认 3；≤1 时走串行路径）。
 */
@Configuration
public class AsyncConfig {

    /** 通用 @Async 执行池：解析、抽取、流式问答等后台任务 */
    @Bean({"applicationTaskExecutor", "taskExecutor"})
    public ThreadPoolTaskExecutor applicationTaskExecutor(SeuAsyncProperties asyncProps) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(asyncProps.coreSize());
        executor.setMaxPoolSize(asyncProps.maxSize());
        executor.setQueueCapacity(asyncProps.queueCapacity());
        executor.setThreadNamePrefix("task-");
        // MDC 跨线程传递：流式问答/解析/抽取等异步任务的日志携带 requestId 等上下文
        executor.setTaskDecorator(new MdcTaskDecorator());
        // 队列满且线程全忙时由调用方线程兜底执行：宁可请求线程临时阻塞，也不静默丢任务
        // （默认 AbortPolicy 会抛 RejectedExecutionException，导致文档解析/抽取任务永久悬挂）
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }

    /** 识图专用池：仅 LLM 识图调用并行（渲染仍在主线程） */
    @Bean("visionTaskExecutor")
    public ThreadPoolTaskExecutor visionTaskExecutor(SeuDocumentProperties docProps) {
        int size = Math.max(1, docProps.visionParallel());
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(size);
        executor.setMaxPoolSize(size);
        executor.setQueueCapacity(64);
        executor.setThreadNamePrefix("vision-");
        // MDC 跨线程传递：识图 worker 日志携带请求上下文（主线程随请求结束清理，worker 有独立快照）
        executor.setTaskDecorator(new MdcTaskDecorator());
        // 队列满时由提交线程（主线程）执行，避免任务丢失
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
