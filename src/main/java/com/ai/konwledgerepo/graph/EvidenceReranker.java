package com.ai.konwledgerepo.graph;

import java.util.List;

/**
 * 证据精排端口：graph 包只依赖本接口，不依赖具体精排实现（RerankClient）。
 * 交叉编码器对 query 与候选文档打分，返回与输入顺序一致的分数（0~1）。
 */
public interface EvidenceReranker {

    /** 编码器是否启用且已配置 API Key（未启用时调用方应降级为按 ES 分截断） */
    boolean isConfigured();

    /** 单次请求允许的最大文档条数（超限时调用方自行截断/分批） */
    int maxDocs();

    /**
     * 对 query 与 documents 逐条打分。
     *
     * @return 与 documents 一一对应的分数（0~1）
     * @throws RuntimeException 未配置 / 调用失败（由调用方决定降级）
     */
    List<Double> rerank(String query, List<String> documents);
}
