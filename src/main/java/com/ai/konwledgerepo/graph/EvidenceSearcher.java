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

    /**
     * 多查询批量检索：全部查询 embedding 一次批量完成（1 次网络往返），再逐查询做三源检索。
     * <p>返回列表与 queries 输入顺序对齐，元素结构同 {@link #searchBySources}；
     * 未做跨查询去重（由调用方统一处理）。默认实现逐查询委托 {@link #searchBySources}
     * （兼容未覆写的实现），VectorSearchService 覆写为真批量。
     */
    default List<List<ChunkEvidence>> searchBySourcesBatch(Long kbId, List<String> queries,
                                                           int chunkTop, int sourceTop) {
        return queries.stream().map(q -> searchBySources(kbId, q, chunkTop, sourceTop)).toList();
    }
}
