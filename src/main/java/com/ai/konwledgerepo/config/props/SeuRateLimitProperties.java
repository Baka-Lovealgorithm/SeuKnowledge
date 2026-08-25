package com.ai.konwledgerepo.config.props;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * seuknowledge.rate-limit.* 配置（/ask 限流与防重）。
 */
@ConfigurationProperties(prefix = "seuknowledge.rate-limit")
public record SeuRateLimitProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("30") int askPerMinute) {
}
