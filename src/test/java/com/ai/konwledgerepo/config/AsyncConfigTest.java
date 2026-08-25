package com.ai.konwledgerepo.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 线程池配置离线单测（不启动完整应用上下文）。
 * <p>
 * 核心回归点：applicationTaskExecutor 必须同时注册 Boot 标准别名 {@code taskExecutor}，
 * 否则无限定符 {@code @Async} 会回退到 SimpleAsyncTaskExecutor（每任务一线程、无上限）
 * ——该缺陷曾导致解析/抽取/流式问答全部脱离线程池管理。
 */
class AsyncConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(AsyncConfig.class, SeuPropertiesConfig.class);

    @Test
    void applicationPoolRegistersTaskExecutorAlias() {
        runner.run(context -> {
            assertThat(context).hasBean("applicationTaskExecutor");
            assertThat(context).hasBean("taskExecutor");
            Object primary = context.getBean("applicationTaskExecutor");
            Object alias = context.getBean("taskExecutor");
            // 别名与主名必须指向同一池实例（Boot 默认双名注册语义）
            assertThat(alias).isSameAs(primary);
            assertThat(alias).isInstanceOf(ThreadPoolTaskExecutor.class);
        });
    }

    @Test
    void applicationPoolUsesDefaults() {
        runner.run(context -> {
            ThreadPoolTaskExecutor pool = context.getBean("taskExecutor", ThreadPoolTaskExecutor.class);
            assertThat(pool.getCorePoolSize()).isEqualTo(8);
            assertThat(pool.getMaxPoolSize()).isEqualTo(32);
            assertThat(pool.getQueueCapacity()).isEqualTo(256);
            assertThat(pool.getThreadNamePrefix()).isEqualTo("task-");
        });
    }

    @Test
    void applicationPoolHonorsConfigProperties() {
        runner.withPropertyValues(
                "seuknowledge.async.core-size=2",
                "seuknowledge.async.max-size=4",
                "seuknowledge.async.queue-capacity=16"
        ).run(context -> {
            ThreadPoolTaskExecutor pool = context.getBean("applicationTaskExecutor", ThreadPoolTaskExecutor.class);
            assertThat(pool.getCorePoolSize()).isEqualTo(2);
            assertThat(pool.getMaxPoolSize()).isEqualTo(4);
            assertThat(pool.getQueueCapacity()).isEqualTo(16);
        });
    }

    @Test
    void applicationPoolUsesCallerRunsRejectionPolicy() {
        runner.run(context -> {
            ThreadPoolTaskExecutor pool = context.getBean("taskExecutor", ThreadPoolTaskExecutor.class);
            RejectedExecutionHandler handler = pool.getThreadPoolExecutor().getRejectedExecutionHandler();
            // 队列满时兜底执行而非抛异常丢任务（默认 AbortPolicy 会让文档解析/抽取任务永久悬挂）
            assertThat(handler).isInstanceOf(ThreadPoolExecutor.CallerRunsPolicy.class);
        });
    }

    @Test
    void visionPoolFollowsParallelismAndUsesCallerRuns() {
        runner.withPropertyValues("seuknowledge.document.vision-parallel=2").run(context -> {
            assertThat(context).hasBean("visionTaskExecutor");
            ThreadPoolTaskExecutor pool = context.getBean("visionTaskExecutor", ThreadPoolTaskExecutor.class);
            assertThat(pool.getCorePoolSize()).isEqualTo(2);
            assertThat(pool.getMaxPoolSize()).isEqualTo(2);
            assertThat(pool.getThreadNamePrefix()).isEqualTo("vision-");
            RejectedExecutionHandler handler = pool.getThreadPoolExecutor().getRejectedExecutionHandler();
            assertThat(handler).isInstanceOf(ThreadPoolExecutor.CallerRunsPolicy.class);
        });
    }

    @Test
    void taskExecutorNameResolvesAsExecutorByTypeLookup() {
        // 模拟 AsyncExecutionAspectSupport 的解析路径：按名 "taskExecutor" 取 Executor 必须成功
        runner.run(context -> {
            Executor executor = context.getBean("taskExecutor", Executor.class);
            assertThat(executor).isInstanceOf(ThreadPoolTaskExecutor.class);
        });
    }

    @Test
    void noAmbiguityForTaskExecutorTypeLookup() {
        // 按类型取 TaskExecutor 必须因存在两个候选 bean 而歧义（applicationTaskExecutor + visionTaskExecutor），
        // 这正是必须显式提供 "taskExecutor" 别名（而非依赖类型查找）的原因
        runner.run(context -> {
            assertThatThrownBy(() -> context.getBean(org.springframework.core.task.TaskExecutor.class))
                    .isInstanceOf(org.springframework.beans.factory.NoUniqueBeanDefinitionException.class);
        });
    }
}
