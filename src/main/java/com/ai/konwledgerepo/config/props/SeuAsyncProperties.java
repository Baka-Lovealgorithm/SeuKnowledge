package com.ai.konwledgerepo.config.props;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * seuknowledge.async.* 配置（通用 @Async 线程池 + 向量化独立线程池）。
 *
 * @param coreSize             通用池核心线程数（解析 / 抽取等无限定符 @Async 共用）
 * @param maxSize              通用池最大线程数（队列满后才扩到该值）
 * @param queueCapacity        通用池队列容量
 * @param vectorCoreSize       向量化池核心线程数（embedding 是外部 API 串行等待，少量并发即可）
 * @param vectorMaxSize        向量化池最大线程数
 * @param vectorQueueCapacity  向量化池队列容量（满时 CallerRuns 兜底，不丢任务）
 */
@ConfigurationProperties(prefix = "seuknowledge.async")
public record SeuAsyncProperties(
        @DefaultValue("8") int coreSize,
        @DefaultValue("32") int maxSize,
        @DefaultValue("256") int queueCapacity,
        @DefaultValue("2") int vectorCoreSize,
        @DefaultValue("4") int vectorMaxSize,
        @DefaultValue("200") int vectorQueueCapacity) {
}
