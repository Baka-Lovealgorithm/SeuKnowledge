package com.ai.konwledgerepo.graph.node;

import com.ai.konwledgerepo.common.ContextPropagator;
import com.ai.konwledgerepo.common.SseStreamContext;
import com.ai.konwledgerepo.config.props.SeuRecallProperties;
import com.ai.konwledgerepo.entity.SourceType;
import com.ai.konwledgerepo.graph.ChunkEvidence;
import com.ai.konwledgerepo.graph.EvidenceSearcher;
import com.ai.konwledgerepo.graph.QaContext;
import com.ai.konwledgerepo.graph.QaContextKey;
import com.ai.konwledgerepo.graph.QaState;
import com.ai.konwledgerepo.tracing.QaTracing;
import com.alibaba.cloud.ai.graph.OverAllState;
import io.opentelemetry.api.trace.Span;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;

/**
 * 知识召回节点（多源）：对每个候选查询做三源混合检索；
 * 跨查询按 dedupKey 累加 RRF rank 分（RAG-Fusion 多路融合），候选池按累加分降序输出。
 */
@Component
public class KnowledgeRecallNode extends QaNodeSupport {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeRecallNode.class);

    private final EvidenceSearcher evidenceSearcher;
    private final int chunkTop;
    private final int sourceTop;
    private final Executor qaExecutor;
    private final boolean parallel;

    public KnowledgeRecallNode(EvidenceSearcher evidenceSearcher,
                               QaTracing qaTracing,
                               SeuRecallProperties recallProps,
                               @org.springframework.beans.factory.annotation.Qualifier("qaTaskExecutor") Executor qaExecutor,
                               com.ai.konwledgerepo.config.props.SeuQaProperties qaProps) {
        super(qaTracing);
        this.evidenceSearcher = evidenceSearcher;
        this.chunkTop = Math.max(1, recallProps.chunkTop());
        this.sourceTop = Math.max(1, recallProps.sourceTop());
        this.qaExecutor = qaExecutor;
        this.parallel = qaProps.parallel();
    }

    @Override
    protected String spanName() {
        return "node/knowledge_recall";
    }

    @Override
    protected Map<String, Object> applyInternal(OverAllState state, Span span) throws Exception {
        long kbId = QaContext.longValue(state, QaContextKey.KB_ID, -1L);
        List<String> queries = QaContext.stringList(state, QaContextKey.QUERIES);
        if (kbId < 0 || queries.isEmpty()) {
            return Map.of(QaContextKey.CHUNKS, List.of(), QaContextKey.NEXT, QaState.RERANK.name());
        }

        SseStreamContext.sendStage("KNOWLEDGE_RECALL", "证据召回（文档 top" + chunkTop + " / 业务知识、问答对各 top" + sourceTop + "）");
        Map<String, ChunkEvidence> firstByKey = new LinkedHashMap<>();
        Map<String, Double> scoreSum = new HashMap<>();
        List<ChunkEvidence> accumulated = QaContext.chunks(
                state.value(QaContextKey.ACCUMULATED_CHUNKS).orElse(List.of()));
        Set<String> excludedKeys = new HashSet<>();
        for (ChunkEvidence acc : accumulated) {
            excludedKeys.add(SourceType.dedupKey(acc.sourceType(), acc.chunkId()));
        }
        if (parallel && queries.size() > 1) {
            List<CompletableFuture<List<ChunkEvidence>>> futures = new ArrayList<>();
            for (String query : queries) {
                String q = query;
                futures.add(CompletableFuture.supplyAsync(
                        ContextPropagator.wrapSupplier(() -> searchForQuery(kbId, q, excludedKeys)), qaExecutor));
            }
            for (int i = 0; i < queries.size(); i++) {
                try {
                    merge(firstByKey, scoreSum, futures.get(i).get(), excludedKeys);
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof Exception ex) throw ex;
                    throw new RuntimeException(cause);
                }
            }
        } else {
            for (String query : queries) {
                merge(firstByKey, scoreSum, searchForQuery(kbId, query, excludedKeys), excludedKeys);
            }
        }
        // 跨查询 RRF 分数累加（RAG-Fusion 多路融合）：score 是检索器内部 kNN+BM25 的 RRF rank 分，
        // 同一证据被多条查询命中时累加，即标准 RAG-Fusion 全 ranker 求和（加法结合律，量纲天然一致）。
        // 候选池按累加分降序输出；List.sort 稳定，同分保首现顺序；并行按查询顺序 get，累加顺序确定。
        List<ChunkEvidence> merged = firstByKey.entrySet().stream()
                .map(e -> e.getValue().withScore(scoreSum.get(e.getKey())))
                .sorted(Comparator.comparingDouble(ChunkEvidence::score).reversed())
                .toList();
        long chunkCount = merged.stream().filter(e -> SourceType.CHUNK.is(e.sourceType())).count();
        long businessCount = merged.stream().filter(e -> SourceType.BUSINESS.is(e.sourceType())).count();
        long qaCount = merged.stream().filter(e -> SourceType.QA.is(e.sourceType())).count();
        log.info("KnowledgeRecall 候选池：CHUNK {} / BUSINESS {} / QA {}，共 {} 条；排除累计证据 {} 条",
                chunkCount, businessCount, qaCount, merged.size(), excludedKeys.size());
        SseStreamContext.sendStage("KNOWLEDGE_RECALL",
                (excludedKeys.size() > 0 ? "排除已保留证据 " + excludedKeys.size() + " 条，" : "")
                        + "文档 " + chunkCount + " · 业务知识 " + businessCount + " · 问答对 " + qaCount);
        span.setAttribute("queries", queries.size());
        span.setAttribute("excluded_accumulated", excludedKeys.size());
        span.setAttribute("chunk_hits", chunkCount);
        span.setAttribute("business_hits", businessCount);
        span.setAttribute("qa_hits", qaCount);
        span.setAttribute("total_hits", merged.size());
        return Map.of(QaContextKey.CHUNKS, merged, QaContextKey.NEXT, QaState.RERANK.name());
    }

    /**
     * 单一查询按三来源（CHUNK/BUSINESS/QA）一次召回并汇总为候选列表。
     * 检索器内部对 query 只向量化一次，在三个来源过滤间复用同一向量。
     */
    private List<ChunkEvidence> searchForQuery(Long kbId, String query, Set<String> excludedKeys) {
        List<ChunkEvidence> result = new ArrayList<>();
        mergeLocal(result, evidenceSearcher.searchBySources(kbId, query, chunkTop, sourceTop), excludedKeys);
        return result;
    }

    /** 本地去重合并（不移除已保留证据——由外层 merge 统一处理） */
    private void mergeLocal(List<ChunkEvidence> target, List<ChunkEvidence> source, Set<String> excludedKeys) {
        for (ChunkEvidence evidence : source) {
            String key = SourceType.dedupKey(evidence.sourceType(), evidence.chunkId());
            if (excludedKeys.contains(key)) {
                continue;
            }
            target.add(evidence);
        }
    }

    /**
     * 跨查询合并：按 dedupKey 无条件累加 RRF rank 分（多查询共同命中 = 强相关信号），
     * 元数据取首次出现的证据；已保留证据（累计池）不参与候选。
     */
    private void merge(Map<String, ChunkEvidence> firstByKey, Map<String, Double> scoreSum,
                       List<ChunkEvidence> source, Set<String> excludedKeys) {
        for (ChunkEvidence evidence : source) {
            String key = SourceType.dedupKey(evidence.sourceType(), evidence.chunkId());
            if (excludedKeys.contains(key)) {
                continue; // 已保留证据不参与 rerank 候选（由累计池保存，AnswerCompose 合并交 AI）
            }
            scoreSum.merge(key, evidence.score(), Double::sum);
            firstByKey.putIfAbsent(key, evidence);
        }
    }
}
