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
import com.alibaba.cloud.ai.graph.action.NodeAction;
import io.opentelemetry.api.trace.Span;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;

/**
 * 知识召回节点（多源）：对每个候选查询做三源混合检索——
 * 文档 chunk（CHUNK，top {chunk-top}）+ 业务知识（BUSINESS，top {source-top}）+ 问答对（QA，top {source-top}），
 * 按 sourceType+chunkId 去重累计为候选池，交精排节点（交叉编码器）分组取配额。
 * 召回条数由 seuknowledge.recall.* 配置（KB_RECALL_CHUNK_TOP / KB_RECALL_SOURCE_TOP 环境变量可覆盖）。
 * 自引入交叉编码器精排后不再使用来源权重（原 chunkWeight/businessWeight/qaWeight 已废弃）。
 */
@Component
public class KnowledgeRecallNode implements NodeAction {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeRecallNode.class);

    private final EvidenceSearcher evidenceSearcher;
    private final QaTracing qaTracing;
    private final int chunkTop;
    private final int sourceTop;
    private final Executor qaExecutor;
    private final boolean parallel;

    public KnowledgeRecallNode(EvidenceSearcher evidenceSearcher,
                               QaTracing qaTracing,
                               SeuRecallProperties recallProps,
                               @org.springframework.beans.factory.annotation.Qualifier("qaTaskExecutor") Executor qaExecutor,
                               com.ai.konwledgerepo.config.props.SeuQaProperties qaProps) {
        this.evidenceSearcher = evidenceSearcher;
        this.qaTracing = qaTracing;
        this.chunkTop = Math.max(1, recallProps.chunkTop());
        this.sourceTop = Math.max(1, recallProps.sourceTop());
        this.qaExecutor = qaExecutor;
        this.parallel = qaProps.parallel();
    }

    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {
        long kbId = QaContext.longValue(state, QaContextKey.KB_ID, -1L);
        List<String> queries = QaContext.stringList(state, QaContextKey.QUERIES);
        if (kbId < 0 || queries.isEmpty()) {
            return Map.of(
                    QaContextKey.CHUNKS, List.of(),
                    QaContextKey.NEXT, QaState.RERANK.name());
        }

        SseStreamContext.sendStage("KNOWLEDGE_RECALL", "证据召回（文档 top" + chunkTop + " / 业务知识、问答对各 top" + sourceTop + "）");
        Span span = qaTracing.begin("node/knowledge_recall");
        try {
            List<ChunkEvidence> merged = new ArrayList<>();
            // 去重键：来源类型 + chunkId，避免不同来源的实体 id 冲突
            Set<String> seen = new HashSet<>();
            // 已保留证据（累计池）：历轮被 rerank 选中进入答案生成的知识。
            // 重试轮将其排除出 rerank 候选（即使再次被 ES 召回也不参与精排），
            // 避免旧证据干扰新召回块的排名；旧证据由 AnswerCompose 合并累计池一并交 AI，信息不丢失。
            List<ChunkEvidence> accumulated = QaContext.chunks(
                    state.value(QaContextKey.ACCUMULATED_CHUNKS).orElse(List.of()));
            Set<String> excludedKeys = new HashSet<>();
            for (ChunkEvidence acc : accumulated) {
                excludedKeys.add(SourceType.dedupKey(acc.sourceType(), acc.chunkId()));
            }
            if (parallel && queries.size() > 1) {
                // 多查询并行：每个查询内 CHUNK/BUSINESS/QA 三来源顺序检索，
                // 查询间并行（虚拟线程阻塞让出），完成后再按查询原顺序 merge 保持输出确定性
                List<CompletableFuture<List<ChunkEvidence>>> futures = new ArrayList<>();
                for (String query : queries) {
                    String q = query;
                    futures.add(CompletableFuture.supplyAsync(
                            ContextPropagator.wrapSupplier(() -> searchForQuery(kbId, q, excludedKeys)), qaExecutor));
                }
                // 收集结果：按查询原顺序 join（超时/异常向上传播）
                for (int i = 0; i < queries.size(); i++) {
                    try {
                        merge(merged, seen, futures.get(i).get(), excludedKeys);
                    } catch (ExecutionException e) {
                        Throwable cause = e.getCause();
                        if (cause instanceof Exception ex) {
                            throw ex;
                        }
                        throw new RuntimeException(cause);
                    }
                }
            } else {
                for (String query : queries) {
                    merge(merged, seen, searchForQuery(kbId, query, excludedKeys), excludedKeys);
                }
            }
            // 阶段详情：多源召回命中统计
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
            return Map.of(
                    QaContextKey.CHUNKS, merged,
                    QaContextKey.NEXT, QaState.RERANK.name());
        } catch (Exception e) {
            span.recordException(e);
            throw e;
        } finally {
            span.end();
        }
    }

    /** 单一查询按三来源（CHUNK/BUSINESS/QA）检索并汇总为候选列表，保持来源顺序 */
    private List<ChunkEvidence> searchForQuery(Long kbId, String query, Set<String> excludedKeys) {
        List<ChunkEvidence> result = new ArrayList<>();
        List<ChunkEvidence> chunkHits = evidenceSearcher.search(kbId, query, chunkTop, List.of(SourceType.CHUNK.value()));
        mergeLocal(result, chunkHits, excludedKeys);
        mergeLocal(result, evidenceSearcher.search(kbId, query, sourceTop, List.of(SourceType.BUSINESS.value())), excludedKeys);
        mergeLocal(result, evidenceSearcher.search(kbId, query, sourceTop, List.of(SourceType.QA.value())), excludedKeys);
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

    private void merge(List<ChunkEvidence> target, Set<String> seen, List<ChunkEvidence> source,
                       Set<String> excludedKeys) {
        for (ChunkEvidence evidence : source) {
            String key = SourceType.dedupKey(evidence.sourceType(), evidence.chunkId());
            if (excludedKeys.contains(key)) {
                continue; // 已保留证据不参与 rerank 候选（由累计池保存，AnswerCompose 合并交 AI）
            }
            if (seen.add(key)) {
                target.add(evidence);
            }
        }
    }
}
