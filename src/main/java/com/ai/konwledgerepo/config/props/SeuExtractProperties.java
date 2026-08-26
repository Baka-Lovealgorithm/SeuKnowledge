package com.ai.konwledgerepo.config.props;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * seuknowledge.extract.* 配置（抽取任务并发控制）。
 */
@ConfigurationProperties(prefix = "seuknowledge.extract")
public record SeuExtractProperties(
        @DefaultValue("2") int concurrencyLimit) {
}