package com.ai.konwledgerepo.graph.node;

import com.ai.konwledgerepo.common.Defaults;
import com.ai.konwledgerepo.graph.AgentConfig;
import com.ai.konwledgerepo.graph.ChunkEvidence;
import com.ai.konwledgerepo.graph.QaContext;
import com.ai.konwledgerepo.graph.QaContextKey;
import com.ai.konwledgerepo.graph.QaState;
import com.ai.konwledgerepo.common.SseStreamContext;
import com.ai.konwledgerepo.tracing.QaTracing;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.trace.Span;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 重试与兜底节点：
 * - 无召回证据 → 诚实兜底（Defaults.INSUFFICIENT_EVIDENCE_ANSWER）
 * - 置信度低于阈值且未达重试上限 → 回 QUERY_REWRITE 扩大召回重试
 * - 置信度低于阈值且重试已用尽 → 显式拒答（Defaults.INSUFFICIENT_EVIDENCE_REFUSAL），
 *   REFS 携带候选证据供用户自查，不再原样输出低分答案
 * - 置信度达标 → 给出当前答案
 */
@Component
public class RetryOrFallbackNode implements NodeAction {

    private static final double THRESHOLD = 0.7;

    private final QaTracing qaTracing;
    private final ObjectMapper objectMapper;

    public RetryOrFallbackNode(QaTracing qaTracing, ObjectMapper objectMapper) {
        this.qaTracing = qaTracing;
        this.objectMapper = objectMapper;
    }

    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {
        Span span = qaTracing.begin("node/retry_fallback");
        try {
            List<ChunkEvidence> chunks = QaContext.chunks(state.value(QaContextKey.CHUNKS).orElse(List.of()));
            // 无召回证据：无法回答，诚实说明
            if (chunks.isEmpty()) {
                SseStreamContext.sendStage("RETRY_FALLBACK", "未召回证据，诚实兜底");
                span.setAttribute("action", "fallback");
                span.setAttribute("reason", "no_evidence");
                return Map.of(
                        QaContextKey.ANSWER, Defaults.INSUFFICIENT_EVIDENCE_ANSWER,
                        QaContextKey.REFS, "[]",
                        QaContextKey.NEXT, QaState.TERMINAL.name());
            }

            double score = QaContext.doubleValue(state, QaContextKey.VERIFY_SCORE, 0.0);
            int retry = QaContext.intValue(state, QaContextKey.RETRY_COUNT, 0);
            int maxRetry = QaContext.intValue(state, QaContextKey.MAX_RETRY, 2);
            boolean noImprovement = QaContext.booleanValue(state, QaContextKey.NO_IMPROVEMENT, false);
            AgentConfig agent = QaContext.agent(state);
            // 自检阈值与重试上限优先取 Agent 配置（动态可调），缺省回退全局默认
            double threshold = (agent == null || agent.verifyThreshold() <= 0) ? THRESHOLD : agent.verifyThreshold();
            int agentMaxRetry = (agent == null || agent.maxRetry() <= 0) ? maxRetry : agent.maxRetry();

            if (score < threshold && retry < agentMaxRetry && !noImprovement) {
                SseStreamContext.sendStage("RETRY_FALLBACK", "自检未通过（得分 " + Math.round(score * 100) + "），准备第 "
                        + (retry + 1) + " 次重试召回");
                span.setAttribute("action", "retry");
                span.setAttribute("score", score);
                span.setAttribute("retry_count", retry + 1);
                return Map.of(
                        QaContextKey.RETRY_COUNT, retry + 1,
                        QaContextKey.NEXT, QaState.QUERY_REWRITE.name());
            }
            // 重试已用尽或新增证据无改善仍低于阈值：显式拒答并附候选证据（不再原样输出低分答案）
            if (score < threshold) {
                if (noImprovement) {
                    SseStreamContext.sendStage("RETRY_FALLBACK", "自检未通过（得分 " + Math.round(score * 100)
                            + "）且新增证据无改善，提前拒答并附候选证据");
                    span.setAttribute("no_improvement", true);
                } else {
                    SseStreamContext.sendStage("RETRY_FALLBACK", "自检未通过（得分 " + Math.round(score * 100)
                            + "），重试已用尽，显式拒答并附候选证据");
                }
                span.setAttribute("action", "refuse");
                span.setAttribute("reason", "score_below_threshold_after_retries");
                span.setAttribute("score", score);
                span.setAttribute("retry_count", retry);
                return Map.of(
                        QaContextKey.ANSWER, Defaults.INSUFFICIENT_EVIDENCE_REFUSAL,
                        QaContextKey.REFS, QaContext.toRefsJson(chunks, objectMapper),
                        QaContextKey.NEXT, QaState.TERMINAL.name());
            }
            // 有证据且达标：给出当前答案
            SseStreamContext.sendStage("RETRY_FALLBACK", "证据充分，输出最终答案");
            span.setAttribute("action", "answer");
            span.setAttribute("score", score);
            span.setAttribute("retry_count", retry);
            return Map.of(QaContextKey.NEXT, QaState.TERMINAL.name());
        } catch (Exception e) {
            span.recordException(e);
            throw e;
        } finally {
            span.end();
        }
    }
}
