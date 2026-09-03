package com.ai.konwledgerepo.service.vector;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.query_dsl.TermsQueryField;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.ai.konwledgerepo.common.BizException;
import com.ai.konwledgerepo.common.ContextPropagator;
import com.ai.konwledgerepo.common.Texts;
import com.ai.konwledgerepo.config.props.SeuEsProperties;
import com.ai.konwledgerepo.entity.ModelUsage;
import com.ai.konwledgerepo.entity.SourceType;
import com.ai.konwledgerepo.graph.ChunkEvidence;
import com.ai.konwledgerepo.graph.EvidenceSearcher;
import com.ai.konwledgerepo.model.ModelFactory;
import com.ai.konwledgerepo.service.knowledgebase.WorkspaceIdResolver;
import com.ai.konwledgerepo.tracing.LlmTrace;
import com.ai.konwledgerepo.tracing.QaTracing;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.stream.IntStream;

/**
 * ES 混合检索：knn（向量相似）+ BM25（关键词）并行查询，按 RRF 融合后按知识库过滤。
 * 支持按来源类型（CHUNK / BUSINESS / QA）过滤，供多源召回使用。
 */
@Service
public class VectorSearchService implements EvidenceSearcher {

    private static final int RRF_K = 60;

    private final ElasticsearchClient esClient;
    private final ModelFactory modelFactory;
    private final WorkspaceIdResolver workspaceIdResolver;
    private final QaTracing qaTracing;
    private final String indexName;
    private final Executor qaExecutor;
    private final boolean parallel;

    public VectorSearchService(ElasticsearchClient esClient,
                               ModelFactory modelFactory,
                               WorkspaceIdResolver workspaceIdResolver,
                               QaTracing qaTracing,
                               SeuEsProperties esProps,
                               @org.springframework.beans.factory.annotation.Qualifier("qaTaskExecutor") Executor qaExecutor,
                               com.ai.konwledgerepo.config.props.SeuQaProperties qaProps) {
        this.esClient = esClient;
        this.modelFactory = modelFactory;
        this.workspaceIdResolver = workspaceIdResolver;
        this.qaTracing = qaTracing;
        this.indexName = esProps.indexName();
        this.qaExecutor = qaExecutor;
        this.parallel = qaProps.parallel();
    }

    /** 全部来源混合检索（兼容旧调用） */
    public List<ChunkEvidence> search(Long kbId, String query, int topK) {
        return search(kbId, query, topK, null);
    }

    /**
     * 混合检索：返回按 RRF 分数排序的证据列表。
     * sourceTypes 为空或 null 时不按来源过滤（检索全部来源）。
     */
    @Override
    public List<ChunkEvidence> search(Long kbId, String query, int topK, List<String> sourceTypes) {
        float[] vector = embedQuery(kbId, query);
        return searchByVector(kbId, query, vector, topK, sourceTypes);
    }

    /**
     * 单查询多来源一次召回：query 只向量化一次，内部按 CHUNK/BUSINESS/QA 分源检索后顺序合并返回。
     * <p>返回顺序 {@code [CHUNK(<=chunkTop), BUSINESS(<=sourceTop), QA(<=sourceTop)]}；
     * 未做跨来源/跨查询去重（由调用方 {@link com.ai.konwledgerepo.graph.node.KnowledgeRecallNode} 统一处理）。
     */
    public List<ChunkEvidence> searchBySources(Long kbId, String query, int chunkTop, int sourceTop) {
        float[] vector = embedQuery(kbId, query);
        List<ChunkEvidence> result = new ArrayList<>();
        result.addAll(searchByVector(kbId, query, vector, chunkTop, List.of(SourceType.CHUNK.value())));
        result.addAll(searchByVector(kbId, query, vector, sourceTop, List.of(SourceType.BUSINESS.value())));
        result.addAll(searchByVector(kbId, query, vector, sourceTop, List.of(SourceType.QA.value())));
        return result;
    }

    /**
     * 向量化查询文本（仅计算 query 向量，供一次检索在多个来源过滤间复用）。
     * 向量值只依赖 query 文本与所用 Embedding 模型，与来源过滤无关。
     */
    private float[] embedQuery(Long kbId, String query) throws BizException {
        // 按知识库归属解析工作空间（快照缓存），模型解析严格限定当前工作空间
        Long workspaceId = workspaceIdResolver.resolve(kbId);
        EmbeddingModel embeddingModel = modelFactory.getEmbeddingModelByUsage(ModelUsage.RETRIEVE.value(), workspaceId);
        return LlmTrace.embed(qaTracing, embeddingModel, query);
    }

    /** 给定查询向量做一次性多来源混合检索（knn+bm25+RRF），sourceTypes 为空或 null 时检索全部来源。 */
    private List<ChunkEvidence> searchByVector(Long kbId, String query, float[] vector, int topK,
                                               List<String> sourceTypes) {
        try {
            List<Float> queryVector = IntStream.range(0, vector.length)
                    .mapToObj(i -> vector[i])
                    .toList();

            List<Hit<Map>> knnHits;
            List<Hit<Map>> bmHits;
            if (parallel) {
                // knn 与 bm25 并行：embedding 完成后两个 ES 调用并发，耗时 = max(knn, bm25)
                CompletableFuture<List<Hit<Map>>> knnF = CompletableFuture.supplyAsync(
                        ContextPropagator.wrapSupplier(() -> {
                            try { return knnSearch(kbId, queryVector, topK, sourceTypes); }
                            catch (IOException e) { throw new RuntimeException(e); }
                        }), qaExecutor);
                CompletableFuture<List<Hit<Map>>> bmF = CompletableFuture.supplyAsync(
                        ContextPropagator.wrapSupplier(() -> {
                            try { return bm25Search(kbId, query, topK, sourceTypes); }
                            catch (IOException e) { throw new RuntimeException(e); }
                        }), qaExecutor);
                try {
                    knnHits = knnF.join();
                    bmHits = bmF.join();
                } catch (CompletionException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof IOException ioe) {
                        throw ioe;
                    }
                    throw new IOException(cause == null ? e.getMessage() : cause.getMessage(), cause);
                }
            } else {
                knnHits = knnSearch(kbId, queryVector, topK, sourceTypes);
                bmHits = bm25Search(kbId, query, topK, sourceTypes);
            }
            return rrfMerge(knnHits, bmHits, topK);
        } catch (Exception e) {
            throw new BizException("检索失败: " + e.getMessage());
        }
    }

    private List<Hit<Map>> knnSearch(Long kbId, List<Float> queryVector, int topK, List<String> sourceTypes)
            throws IOException {
        SearchResponse<Map> resp = esClient.search(s -> s
                        .index(indexName)
                        .knn(k -> k.field(ChunkDocFields.CONTENT_VECTOR)
                                .queryVector(queryVector)
                                .k(topK)
                                .numCandidates(Math.max(50, topK * 5))
                                .filter(buildKbFilter(kbId, sourceTypes)))
                        .size(topK),
                Map.class);
        return resp.hits().hits();
    }

    private List<Hit<Map>> bm25Search(Long kbId, String query, int topK, List<String> sourceTypes)
            throws IOException {
        Query sourceFilter = sourceTypeFilter(sourceTypes);
        SearchResponse<Map> resp = esClient.search(s -> s
                        .index(indexName)
                        .query(q -> q.bool(b -> {
                            // 正文为主，章节标题语义强（boost 更高），文档名辅助匹配（低权重）
                            b.must(m -> m.multiMatch(mm -> mm
                                    .fields(ChunkDocFields.CONTENT + "^1.0",
                                            ChunkDocFields.TITLE + "^2.0",
                                            ChunkDocFields.DOC_NAME + "^0.5")
                                    .query(query)));
                            b.filter(f -> f.term(t -> t.field(ChunkDocFields.KB_ID).value(kbId)));
                            if (sourceFilter != null) {
                                b.filter(sourceFilter);
                            }
                            return b;
                        }))
                        .size(topK),
                Map.class);
        return resp.hits().hits();
    }

    /** 构建 knn 过滤：知识库 + 可选来源类型（bool filter 多个条件） */
    private Query buildKbFilter(Long kbId, List<String> sourceTypes) {
        BoolQuery.Builder bool = new BoolQuery.Builder();
        bool.filter(f -> f.term(t -> t.field(ChunkDocFields.KB_ID).value(kbId)));
        Query sourceFilter = sourceTypeFilter(sourceTypes);
        if (sourceFilter != null) {
            bool.filter(sourceFilter);
        }
        return bool.build()._toQuery();
    }

    /**
     * 来源类型过滤。CHUNK 使用"等于 CHUNK 或字段缺失"语义，
     * 兼容本特性上线前已索引、无 sourceType 字段的存量 chunk。
     */
    private Query sourceTypeFilter(List<String> sourceTypes) {
        if (sourceTypes == null || sourceTypes.isEmpty()) {
            return null;
        }
        if (sourceTypes.size() == 1 && SourceType.CHUNK.value().equals(sourceTypes.get(0))) {
            return Query.of(q -> q.bool(b -> b
                    .should(s -> s.terms(t -> t.field(ChunkDocFields.SOURCE_TYPE)
                            .terms(TermsQueryField.of(tf -> tf.value(List.of(FieldValue.of(SourceType.CHUNK.value())))))))
                    .should(s -> s.bool(nb -> nb.mustNot(mn -> mn.exists(e -> e.field(ChunkDocFields.SOURCE_TYPE)))))
                    .minimumShouldMatch("1")));
        }
        return Query.of(q -> q.terms(t -> t.field(ChunkDocFields.SOURCE_TYPE)
                .terms(TermsQueryField.of(tf -> tf.value(
                        sourceTypes.stream().map(FieldValue::of).toList())))));
    }

    private List<ChunkEvidence> rrfMerge(List<Hit<Map>> knnHits, List<Hit<Map>> bmHits, int topK) {
        // chunkId -> 累积分数 + 最近一条 hit 的 source
        Map<Long, double[]> scores = new LinkedHashMap<>();
        Map<Long, Map> sources = new LinkedHashMap<>();
        accumulate(scores, sources, knnHits);
        accumulate(scores, sources, bmHits);

        List<ChunkEvidence> result = new ArrayList<>();
        for (Map.Entry<Long, double[]> e : scores.entrySet()) {
            Map src = sources.get(e.getKey());
            result.add(new ChunkEvidence(
                    toLong(src.get(ChunkDocFields.CHUNK_ID)),
                    toLong(src.get(ChunkDocFields.DOC_ID)),
                    toLong(src.get(ChunkDocFields.KB_ID)),
                    Texts.str(src.get(ChunkDocFields.DOC_NAME)),
                    src.get(ChunkDocFields.PAGE_NUM) == null ? 0 : ((Number) src.get(ChunkDocFields.PAGE_NUM)).intValue(),
                    src.get(ChunkDocFields.SOURCE_TYPE) == null ? SourceType.CHUNK.value() : String.valueOf(src.get(ChunkDocFields.SOURCE_TYPE)),
                    src.get(ChunkDocFields.TITLE) == null ? "" : String.valueOf(src.get(ChunkDocFields.TITLE)),
                    Texts.str(src.get(ChunkDocFields.CONTENT)),
                    e.getValue()[0]));
        }
        result.sort(Comparator.comparingDouble(ChunkEvidence::score).reversed());
        return result.stream().limit(topK).toList();
    }

    private void accumulate(Map<Long, double[]> scores,
                            Map<Long, Map> sources,
                            List<Hit<Map>> hits) {
        for (int i = 0; i < hits.size(); i++) {
            Hit<Map> hit = hits.get(i);
            if (hit.source() == null) {
                continue;
            }
            Long chunkId = toLong(hit.source().get(ChunkDocFields.CHUNK_ID));
            if (chunkId == null) {
                continue;
            }
            double[] s = scores.computeIfAbsent(chunkId, k -> new double[]{0.0});
            s[0] += 1.0 / (RRF_K + i + 1);
            sources.putIfAbsent(chunkId, hit.source());
        }
    }

    private static Long toLong(Object o) {
        return o == null ? null : ((Number) o).longValue();
    }
}
