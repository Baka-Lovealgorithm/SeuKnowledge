package com.ai.konwledgerepo.config.props;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * seuknowledge.recall.* 配置（知识召回**策略参数**）。
 * 与 rerank 参数分离：本配置控制「每查询每来源召回多少条进精排候选」；
 * 精排后取多少条由 seuknowledge.rerank.* 控制。
 */
@ConfigurationProperties(prefix = "seuknowledge.recall")
public record SeuRecallProperties(
        @DefaultValue("15") int chunkTop,
        @DefaultValue("8") int sourceTop) {
}
