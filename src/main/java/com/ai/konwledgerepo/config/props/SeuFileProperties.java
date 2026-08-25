package com.ai.konwledgerepo.config.props;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * seuknowledge.file.* 配置（文件存储）。
 */
@ConfigurationProperties(prefix = "seuknowledge.file")
public record SeuFileProperties(
        @DefaultValue("./data/files") String storagePath,
        @DefaultValue("20971520") long maxSize) {
}
