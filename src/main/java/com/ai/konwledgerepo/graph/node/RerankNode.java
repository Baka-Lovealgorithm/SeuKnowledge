package com.ai.konwledgerepo.graph.node;

import com.ai.konwledgerepo.common.SseStreamContext;
import com.ai.konwledgerepo.config.props.SeuRerankProperties;
import com.ai.konwledgerepo.entity.SourceType;
import com.ai.konwledgerepo.graph.ChunkEvidence;
import com.ai.konwledgerepo.graph.EvidenceReranker;
import com.ai.konwledgerepo.graph.QaContext;
import com.ai.konwledgerepo.graph.QaContextKey;
import com.ai.konwledgerepo.graph.QaState;
import com.ai.konwledgerepo.model.ModelFactory;
import com.ai.konwledgerepo.tracing.QaTracing;
import com.alibaba.cloud.ai.graph.OverAllState;
import io.opentelemetry.api.trace.Span;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 重排节点（交叉编码器精排）：对召回候选按来源分组，用重排模型打分后组内截断——
 * CHUNK（文档 chunk）取 top {chunk-top}，业务知识+问答对合并取 top {other-top}，合并为最终证据。
 * 重排模型按当前工作空间从模型配置体系解析（kb_model_config model_type=RERANK，DASHSCOPE / OpenAI 兼容）；
 * 未配置 / 编码器异常 / 超时时自动降级：按 ES 分同配额截断，保证问答链路可用。
 * 自引入交叉编码器后不再使用 agent.topN 全局截断与来源权重（权重机制已废弃）。
 */
@Component
public class RerankNode extends QaNodeSupport {

    private static final Logger log = LoggerFactory.getLogger(RerankNode.class);

    private final ModelFactory modelFactory;
    private final int chunkTop;
    private final int otherTop;

    public RerankNode(ModelFactory modelFactory,
                      QaTracing qaTracing,
                      SeuRerankProperties rerankProps) {
        super(qaTracing);
        this.modelFactory = modelFactory;
        this.chunkTop = Math.max(0, rerankProps.chunkTop());
        this.otherTop = Math.max(0, rerankProps.otherTop());
    }

    @Override
    protected String spanName() {
        return "node/rerank";
    }

    @Override
    protected Map<String, Object> applyInternal(OverAllState state, Span span) throws Exception {
        SseStreamContext.sendStage("RERANK", "证据精排（交叉编码器：文档 " + chunkTop
                + " / 业务知识+问答对 " + otherTop + "）");
        List<ChunkEvidence> chunks = QaContext.chunks(state.value(QaContextKey.CHUNKS).orElse(List.of()));
        if (chunks.isEmpty()) {
            span.setAttribute("reranked_count", 0);
            return Map.of(
                    QaContextKey.CHUNKS, List.of(),
                    QaContextKey.NEXT, QaState.ANSWER_COMPOSE.name());
        }
        String question = state.value(QaContextKey.RAW_QUESTION).map(String::valueOf).orElse("");
        List<ChunkEvidence> chunkGroup = chunks.stream()
                .filter(c -> SourceType.CHUNK.is(c.sourceType()))
                .toList();
        List<ChunkEvidence> otherGroup = chunks.stream()
                .filter(c -> !SourceType.CHUNK.is(c.sourceType()))
                .toList();

        long workspaceId = QaContext.longValue(state, QaContextKey.WORKSPACE_ID, 0L);
        Optional<EvidenceReranker> rerankerOpt = modelFactory.getReranker(workspaceId);
        boolean reranked = rerankerOpt.filter(EvidenceReranker::isConfigured).isPresent();
        List<ChunkEvidence> rerankedChunks;
        List<ChunkEvidence> rerankedOthers;
        if (reranked) {
            try {
                EvidenceReranker reranker = rerankerOpt.get();
                rerankedChunks = rerankAndCut(chunkGroup, question, chunkTop, reranker);
                rerankedOthers = rerankAndCut(otherGroup, question, otherTop, reranker);
                log.info("交叉编码器精排成功: query={} 配额(chunk={} other={}) 实际(chunk={} other={})",
                        question.length() > 60 ? question.substring(0, 60) + "..." : question,
                        chunkTop, otherTop, rerankedChunks.size(), rerankedOthers.size());
                SseStreamContext.sendStage("RERANK", "证据精排完成：文档 " + rerankedChunks.size()
                        + " · 业务知识+问答对 " + rerankedOthers.size());
            } catch (Exception e) {
                log.warn("交叉编码器精排失败，降级按 ES 分排序: {}", e.getMessage());
                span.setAttribute("rerank_degraded", true);
                span.setAttribute("rerank_error", e.getMessage());
                rerankedChunks = cutByEsScore(chunkGroup, chunkTop);
                rerankedOthers = cutByEsScore(otherGroup, otherTop);
                reranked = false;
            }
        } else {
            rerankedChunks = cutByEsScore(chunkGroup, chunkTop);
            rerankedOthers = cutByEsScore(otherGroup, otherTop);
        }

        List<ChunkEvidence> result = new ArrayList<>(rerankedChunks);
        result.addAll(rerankedOthers);
        span.setAttribute("rerank_used", reranked);
        span.setAttribute("chunk_count", rerankedChunks.size());
        span.setAttribute("other_count", rerankedOthers.size());
        span.setAttribute("reranked_count", result.size());
        List<ChunkEvidence> accumulated = QaContext.chunks(
                state.value(QaContextKey.ACCUMULATED_CHUNKS).orElse(List.of()));
        List<ChunkEvidence> mergedAccumulated = QaContext.mergeEvidence(accumulated, result);
        if (mergedAccumulated.size() > accumulated.size()) {
            log.info("累计证据池：本轮新增 {} 条，累计 {} 条", mergedAccumulated.size() - accumulated.size(),
                    mergedAccumulated.size());
        }
        return Map.of(
                QaContextKey.CHUNKS, result,
                QaContextKey.ACCUMULATED_CHUNKS, mergedAccumulated,
                QaContextKey.NEXT, QaState.ANSWER_COMPOSE.name());
    }

    /** 调交叉编码器打分并按分排序取前 quota（候选超 max-docs 时按 ES 分粗筛截断，防单次输入超 30K token） */
    private List<ChunkEvidence> rerankAndCut(List<ChunkEvidence> group, String question, int quota,
                                             EvidenceReranker reranker) {
        if (group.isEmpty() || quota <= 0) {
            return List.of();
        }
        List<ChunkEvidence> capped = group.stream()
                .sorted(Comparator.comparingDouble(ChunkEvidence::score).reversed())
                .limit(reranker.maxDocs())
                .toList();
        List<String> docs = capped.stream()
                .map(c -> metadataPrefix(c) + c.content())
                .toList();
        List<Double> scores = reranker.rerank(question, docs);
        List<ChunkEvidence> scored = new ArrayList<>(capped.size());
        for (int i = 0; i < capped.size(); i++) {
            ChunkEvidence c = capped.get(i);
            double s = i < scores.size() ? scores.get(i) : 0.0;
            scored.add(new ChunkEvidence(c.chunkId(), c.docId(), c.kbId(), c.docName(),
                    c.pageNum(), c.sourceType(), c.title(), c.content(), s));
        }
        scored.sort(Comparator.comparingDouble(ChunkEvidence::score).reversed());
        return scored.stream().limit(quota).toList();
    }

    /** 降级：按 ES 分排序取前 quota（无编码器时同配额，保证链路可用） */
    private List<ChunkEvidence> cutByEsScore(List<ChunkEvidence> group, int quota) {
        if (group.isEmpty() || quota <= 0) {
            return List.of();
        }
        return group.stream()
                .sorted(Comparator.comparingDouble(ChunkEvidence::score).reversed())
                .limit(quota)
                .toList();
    }

    /**
     * 重排输入前置元数据：文档名 + 章节标题（空则跳过），帮助交叉编码器感知文档结构。
     * 标题与文档名相同（如封面页）时不重复拼接。
     */
    static String metadataPrefix(ChunkEvidence c) {
        if (c == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        String docName = c.docName() == null ? "" : c.docName().trim();
        String title = c.title() == null ? "" : c.title().trim();
        if (!docName.isEmpty()) {
            sb.append("文档：").append(docName).append("；");
        }
        if (!title.isEmpty() && !title.equals(docName)) {
            sb.append("章节：").append(title).append("；");
        }
        return sb.length() > 0 ? sb + "\n" : "";
    }
}
