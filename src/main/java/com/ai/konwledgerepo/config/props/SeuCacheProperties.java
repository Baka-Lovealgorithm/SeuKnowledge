package com.ai.konwledgerepo.config.props;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * seuknowledge.cache.* 配置（各类缓存 TTL）。
 */
@ConfigurationProperties(prefix = "seuknowledge.cache")
public record SeuCacheProperties(
        @DefaultValue("300") long memberTtlSeconds,
        @DefaultValue("600") long modelTtlSeconds,
        @DefaultValue("600") long agentTtlSeconds,
        @DefaultValue("300") long kbTtlSeconds,
        @DefaultValue("300") long kbCountTtlSeconds,
        @DefaultValue("60") long kbListTtlSeconds,
        @DefaultValue("60") long sessionTtlSeconds,
        @DefaultValue("600") long historyTtlSeconds,
        @DefaultValue("86400") long taskTtlSeconds) {
}
