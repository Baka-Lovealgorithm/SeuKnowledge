package com.ai.konwledgerepo.config.props;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * seuknowledge.document.* 配置（文档解析 / 识图 / LlamaParse）。
 */
@ConfigurationProperties(prefix = "seuknowledge.document")
public record SeuDocumentProperties(
        @DefaultValue("true") boolean visionParsing,
        @DefaultValue("true") boolean visionAuto,
        @DefaultValue("50") int visionMinText,
        @DefaultValue("100") int visionDpi,
        @DefaultValue("3") int visionParallel,
        @DefaultValue("800") int chunkSize,
        @DefaultValue("120") int chunkOverlap,
        LlamaParse llamaparse) {

    /**
     * seuknowledge.document.llamaparse.* 配置。
     */
    public record LlamaParse(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("") String apiKey,
            @DefaultValue("https://api.cloud.llamaindex.ai") String baseUrl,
            @DefaultValue("cost_effective") String tier,
            @DefaultValue("latest") String version,
            @DefaultValue("ch_sim") String language,
            @DefaultValue("5") long pollIntervalSeconds,
            @DefaultValue("900") long maxPollSeconds,
            @DefaultValue("") String parsingInstruction,
            @DefaultValue("true") boolean takeScreenshot,
            @DefaultValue("true") boolean fillMissingPages,
            @DefaultValue("./data/llamaparse") String outputDir) {
    }
}
