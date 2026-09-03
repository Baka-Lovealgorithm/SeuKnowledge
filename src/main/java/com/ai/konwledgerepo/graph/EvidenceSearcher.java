package com.ai.konwledgerepo.graph;

import java.util.List;

/**
 * 证据检索端口：graph 包只依赖本接口，不依赖具体检索实现（VectorSearchService）。
 * 混合检索：向量（knn）+ 关键词（BM25）融合，按来源类型过滤（CHUNK / BUSINESS / QA）。
 */
public interface EvidenceSearcher {

    /**
     * 检索证据列表。
     *
     * @param kbId        知识库 id（限定检索范围）
     * @param query       查询文本
     * @param topK        返回条数上限
     * @param sourceTypes 来源过滤（null 或空 = 全部来源）
     */
    List<ChunkEvidence> search(Long kbId, String query, int topK, List<String> sourceTypes);

    /**
     * 单查询多来源一次召回：query 只向量化一次，内部按 CHUNK/BUSINESS/QA 分源检索后顺序合并返回。
     * <p>返回顺序 {@code [CHUNK(<=chunkTop), BUSINESS(<=sourceTop), QA(<=sourceTop)]}；
     * 未做跨来源/跨查询去重（由调用方统一处理）。
     */
    List<ChunkEvidence> searchBySources(Long kbId, String query, int chunkTop, int sourceTop);
}
