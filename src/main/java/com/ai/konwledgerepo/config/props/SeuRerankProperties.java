package com.ai.konwledgerepo.config.props;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * seuknowledge.rerank.* 配置（交叉编码器精排**策略参数**）。
 * 重排模型的供应商/端点/模型名/apiKey 由模型配置页（kb_model_config，model_type=RERANK）维护，
 * 本配置仅保留与模型无关的全局策略参数。
 */
@ConfigurationProperties(prefix = "seuknowledge.rerank")
public record SeuRerankProperties(
        @DefaultValue("4") int chunkTop,
        @DefaultValue("4") int otherTop,
        @DefaultValue("20") int maxDocs,
        @DefaultValue("1500") int maxCharsPerDoc,
        @DefaultValue("10000") long timeoutMs) {
}
