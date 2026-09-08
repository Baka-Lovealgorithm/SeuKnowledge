package com.ai.konwledgerepo.config;

import com.ai.konwledgerepo.common.MdcTaskDecorator;
import com.ai.konwledgerepo.config.props.SeuAsyncProperties;
import com.ai.konwledgerepo.config.props.SeuDocumentProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
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
 * {@code @Async} 会因多个 TaskExecutor bean 类型歧义且无名匹配 bean 而回退到
 * {@code SimpleAsyncTaskExecutor}（每任务一线程、无上限、无拒绝策略）——已确认为此前线上缺陷，
 * 该别名不可删除。池容量可经 {@code seuknowledge.async.*} 调优（默认 8/32/256）。
 * <p>
 * visionTaskExecutor：PDF 识图专用有界线程池（仅 LLM 网络调用并行，页面渲染仍在主线程）。
 * 并行度由 {@code seuknowledge.document.vision-parallel} 控制（默认 3；≤1 时走串行路径）。
 * <p>
 * vectorTaskExecutor：向量化（embedding + ES 写入）专用小池，把"确认向量化/重建向量"从通用池
 * 里摘出来，避免被分钟级抽取长任务排在后面（默认 2/4/200，见 {@link #vectorTaskExecutor}）。
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

    /**
     * 向量化专用池：{@code VectorIngestionService.ingestAsync} 走本池，不再与解析/抽取抢通用池。
     * <p>
     * 动机：精修「确认」与「重建向量」都是往通用池丢一个向量化任务，而该池同时装着分钟级的抽取任务
     * （core 8）。一批确认会被抽取长任务排在后面——此时文档已 {@code curateStatus=null + SUCCESS}、
     * 已离开精修队列，却还没有任何向量，用户侧就是"一片成功但全都检索不到"，且这段时长没有上界。
     * embedding 是纯外部 API 等待（服务端还有批大小限制），2~4 并发足够，独立小池即可解耦。
     */
    @Bean("vectorTaskExecutor")
    public ThreadPoolTaskExecutor vectorTaskExecutor(SeuAsyncProperties asyncProps) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        int core = Math.max(1, asyncProps.vectorCoreSize());
        executor.setCorePoolSize(core);
        // max 必须 ≥ core，否则 initialize() 直接抛异常（配置被写坏时宁可放大 max，也不要让应用起不来）
        executor.setMaxPoolSize(Math.max(core, Math.max(1, asyncProps.vectorMaxSize())));
        executor.setQueueCapacity(Math.max(0, asyncProps.vectorQueueCapacity()));
        executor.setThreadNamePrefix("embed-");
        executor.setTaskDecorator(new MdcTaskDecorator());
        // 与通用池同口径：满时由提交线程兜底执行，宁可慢也不静默丢向量化任务
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }

    /** 流式问答专用池：per-task 虚拟线程，SSE 长会话不占平台线程池（解析/抽取），保留 MDC 传递 */
    @Bean("streamVirtualExecutor")
    public SimpleAsyncTaskExecutor streamVirtualExecutor() {
        SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor();
        executor.setVirtualThreads(true);          // Spring 6.1+，每任务一虚拟线程，阻塞自动让出载体
        executor.setTaskDecorator(new MdcTaskDecorator()); // requestId/sessionId 跨线程复用现有装饰器
        executor.setThreadNamePrefix("stream-");
        return executor;
    }
}
