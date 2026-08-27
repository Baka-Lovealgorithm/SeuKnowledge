package com.ai.konwledgerepo.config.props;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * seuknowledge.qa.* 配置（问答链路全局默认）。
 */
@ConfigurationProperties(prefix = "seuknowledge.qa")
public record SeuQaProperties(
        @DefaultValue("20") int messageWindow,
        @DefaultValue("2") int maxRetry,
        @DefaultValue("32") int concurrencyLimit,
        @DefaultValue("30") int concurrencyTimeoutSeconds,
        @DefaultValue("true") boolean parallel,
        @DefaultValue("false") boolean earlyAbort,
        @DefaultValue("false") boolean partialAnswer,
        @DefaultValue("0.4") double partialFloor,
        @DefaultValue("false") boolean verifyJsonMode,
        @DefaultValue("60") int llmTimeoutSeconds,
        @DefaultValue("200") int qaTimeoutSeconds) {
}
