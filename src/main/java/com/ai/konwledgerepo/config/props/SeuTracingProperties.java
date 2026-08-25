package com.ai.konwledgerepo.config.props;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * seuknowledge.tracing.* 配置（OpenTelemetry / Langfuse）。
 */
@ConfigurationProperties(prefix = "seuknowledge.tracing")
public record SeuTracingProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("") String endpoint,
        @DefaultValue("seuknowledge") String serviceName,
        @DefaultValue("") String langfusePublicKey,
        @DefaultValue("") String langfuseSecretKey) {
}
