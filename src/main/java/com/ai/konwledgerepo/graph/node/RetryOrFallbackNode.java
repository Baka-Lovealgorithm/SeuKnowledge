package com.ai.konwledgerepo.graph.node;

import com.ai.konwledgerepo.common.Defaults;
import com.ai.konwledgerepo.config.props.SeuQaProperties;
import com.ai.konwledgerepo.graph.AgentConfig;
import com.ai.konwledgerepo.graph.ChunkEvidence;
import com.ai.konwledgerepo.graph.QaContext;
import com.ai.konwledgerepo.graph.QaContextKey;
import com.ai.konwledgerepo.graph.QaState;
import com.ai.konwledgerepo.common.SseStreamContext;
import com.ai.konwledgerepo.tracing.QaTracing;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.trace.Span;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 重试与兜底节点。
 */
@Component
public class RetryOrFallbackNode extends QaNodeSupport {

    private static final double THRESHOLD = 0.7;

    private final ObjectMapper objectMapper;
    private final boolean partialAnswer;
    private final double partialFloor;

    public RetryOrFallbackNode(QaTracing qaTracing, ObjectMapper objectMapper, SeuQaProperties qaProps) {
        super(qaTracing);
        this.objectMapper = objectMapper;
        this.partialAnswer = qaProps.partialAnswer();
        this.partialFloor = qaProps.partialFloor();
    }

    @Override
    protected String spanName() {
        return "node/retry_fallback";
    }

    @Override
    protected Map<String, Object> applyInternal(OverAllState state, Span span) throws Exception {
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
                        QaContextKey.PREV_ANSWER, state.value(QaContextKey.ANSWER).map(String::valueOf).orElse(""),
                        QaContextKey.RETRY_COUNT, retry + 1,
                        QaContextKey.NEXT, QaState.QUERY_REWRITE.name());
            }
            // 重试已用尽或新增证据无改善仍低于阈值：三级出口
            if (score < threshold) {
                if (tryPartialAnswer(state, chunks, score, noImprovement, span)) {
                    return Map.of(
                            QaContextKey.ANSWER, buildPartialAnswer(state),
                            QaContextKey.REFS, QaContext.toRefsJson(chunks, objectMapper),
                            QaContextKey.NEXT, QaState.TERMINAL.name());
                }
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
    }

    /**
     * 部分回答判定：开关开启、无矛盾断言（矛盾=与证据冲突，绝不出）、分数≥下限时，
     * 输出合成答案 + 缺漏声明 + 候选证据，替代显式拒答。
     */
    private boolean tryPartialAnswer(OverAllState state, List<ChunkEvidence> chunks, double score,
                                     boolean noImprovement, Span span) {
        if (!partialAnswer) {
            return false;
        }
        List<String> contradicted = QaContext.stringList(state, QaContextKey.CONTRADICTED_CLAIMS);
        if (!contradicted.isEmpty()) {
            return false;
        }
        if (score < partialFloor) {
            return false;
        }
        SseStreamContext.sendStage("RETRY_FALLBACK", "自检未通过（得分 " + Math.round(score * 100)
                + "），无矛盾断言且达部分回答下限，输出部分答案并提示缺漏");
        span.setAttribute("action", "partial");
        span.setAttribute("reason", "score_below_threshold_no_contradiction");
        span.setAttribute("score", score);
        span.setAttribute("retry_count", QaContext.intValue(state, QaContextKey.RETRY_COUNT, 0));
        if (noImprovement) {
            span.setAttribute("no_improvement", true);
        }
        return true;
    }

    /** 组装部分回答：前置声明 + 合成答案（保留原引用）+ 缺漏声明（缺失信息为空时省略） */
    private String buildPartialAnswer(OverAllState state) {
        String composed = state.value(QaContextKey.ANSWER).map(String::valueOf).orElse("");
        String missing = state.value(QaContextKey.MISSING_INFO).map(String::valueOf).orElse("").trim();
        StringBuilder sb = new StringBuilder(Defaults.PARTIAL_ANSWER_PREFIX);
        if (composed != null && !composed.isBlank()) {
            sb.append(composed);
        }
        if (!missing.isEmpty() && !"无".equals(missing)) {
            sb.append(Defaults.PARTIAL_ANSWER_MISSING_SUFFIX.formatted(missing));
        }
        return sb.toString();
    }
}
