package com.ai.konwledgerepo.config.props;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/**
 * seuknowledge.document.* 配置（文档解析 / 识图 / LlamaParse / 清洗）。
 * <p>注意：record 必须保持单一 canonical 构造器（Spring Boot 对 @ConfigurationProperties
 * record 依赖 canonical 构造器做构造器绑定；额外构造器会使绑定退化为无参构造而失败）。
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
        LlamaParse llamaparse,
        Clean clean) {

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

    /**
     * seuknowledge.document.clean.* 配置（P1 规则清洗）。
     * <p>
     * 处置名单语义：规则命中后查名单——ID 在 {@code autoDropRules} → AUTO-DROP（不进 ES）；
     * 在 {@code suspectRules} → SUSPECT（照常入库进 ES，仅带清洗标记）；都不在 → 仅统计不动作
     * （fail-safe：删除权必须显式授予，规则代码本身没有删除权）。
     */
    public record Clean(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("0.8") double headerFooterRatio,
            @DefaultValue("3") int headerFooterWindow,
            @DefaultValue("50") int headerFooterMaxLen,
            @DefaultValue("10") int shortMinLen,
            @DefaultValue("0.9") double nearDupSim,
            @DefaultValue("0.6") double templateRatio,
            @DefaultValue("80") int longDupMinLen,
            @DefaultValue("30") int figureTitleMaxLen,
            @DefaultValue("40") int figureContentMinLen,
            @DefaultValue({"A1", "A2", "A5", "C1"}) List<String> autoDropRules,
            @DefaultValue({"A3", "A4", "B1", "B2", "B3", "C2", "C3", "P5"}) List<String> suspectRules) {

        /** 测试/纯规则场景的默认实例（与 @DefaultValue 一致） */
        public static Clean defaults() {
            return new Clean(true, 0.8, 3, 50, 10, 0.9, 0.6, 80, 30, 40,
                    List.of("A1", "A2", "A5", "C1"),
                    List.of("A3", "A4", "B1", "B2", "B3", "C2", "C3", "P5"));
        }
    }
}
