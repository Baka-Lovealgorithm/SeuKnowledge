package com.ai.konwledgerepo.config.props;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * seuknowledge.es.* 配置（Elasticsearch 检索）。
 */
@ConfigurationProperties(prefix = "seuknowledge.es")
public record SeuEsProperties(
        @DefaultValue("kb_chunk") String indexName,
        @DefaultValue("1024") int vectorDimensions) {
}
