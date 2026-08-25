package com.ai.konwledgerepo.config.props;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * seuknowledge.async.* 配置（通用 @Async 线程池）。
 */
@ConfigurationProperties(prefix = "seuknowledge.async")
public record SeuAsyncProperties(
        @DefaultValue("8") int coreSize,
        @DefaultValue("32") int maxSize,
        @DefaultValue("256") int queueCapacity) {
}
