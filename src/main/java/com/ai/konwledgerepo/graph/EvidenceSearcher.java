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
}
