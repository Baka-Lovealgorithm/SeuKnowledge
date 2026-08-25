package com.ai.konwledgerepo.graph;

import com.ai.konwledgerepo.common.BizException;
import com.ai.konwledgerepo.common.QaConcurrencyGuard;
import com.ai.konwledgerepo.common.Texts;
import com.ai.konwledgerepo.graph.node.AnswerComposeNode;
import com.ai.konwledgerepo.graph.node.AnswerVerifyNode;
import com.ai.konwledgerepo.graph.node.ChatOnlyNode;
import com.ai.konwledgerepo.graph.node.IntentRouteNode;
import com.ai.konwledgerepo.graph.node.KnowledgeRecallNode;
import com.ai.konwledgerepo.graph.node.QueryRewriteNode;
import com.ai.konwledgerepo.graph.node.RerankNode;
import com.ai.konwledgerepo.graph.node.RetryOrFallbackNode;
import com.ai.konwledgerepo.graph.node.TerminalNode;
import com.ai.konwledgerepo.tracing.QaTracing;
import com.ai.konwledgerepo.tracing.TokenAccumulator;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.KeyStrategyFactory;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.alibaba.cloud.ai.graph.StateGraph.END;
import static com.alibaba.cloud.ai.graph.StateGraph.START;
import static com.alibaba.cloud.ai.graph.action.AsyncEdgeAction.edge_async;
import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;

/**
 * 问答链路状态图组装与执行入口。
 *
 * 图结构：
 * START → INTENT_ROUTE →(条件) QUERY_REWRITE → KNOWLEDGE_RECALL → RERANK →
 * ANSWER_COMPOSE → ANSWER_VERIFY → RETRY_FALLBACK →(条件) QUERY_REWRITE（重试） | END；
 * INTENT_ROUTE →(闲聊) CHAT_ONLY → END。
 */
@Component
public class QaGraphRunner {

    private static final Logger log = LoggerFactory.getLogger(QaGraphRunner.class);

    private final CompiledGraph compiledGraph;
    private final QaTracing qaTracing;
    private final QaConcurrencyGuard guard;

    public QaGraphRunner(IntentRouteNode intentRouteNode,
                         QueryRewriteNode queryRewriteNode,
                         KnowledgeRecallNode knowledgeRecallNode,
                         RerankNode rerankNode,
                         AnswerComposeNode answerComposeNode,
                         AnswerVerifyNode answerVerifyNode,
                         RetryOrFallbackNode retryOrFallbackNode,
                         ChatOnlyNode chatOnlyNode,
                         TerminalNode terminalNode,
                         QaTracing qaTracing,
                         QaConcurrencyGuard guard) {
        this.qaTracing = qaTracing;
        this.guard = guard;
        try {
            this.compiledGraph = build(intentRouteNode, queryRewriteNode, knowledgeRecallNode,
                    rerankNode, answerComposeNode, answerVerifyNode, retryOrFallbackNode, chatOnlyNode, terminalNode);
        } catch (Exception e) {
            throw new IllegalStateException("问答状态图构建失败", e);
        }
    }

    private static CompiledGraph build(IntentRouteNode intentRouteNode,
                                       QueryRewriteNode queryRewriteNode,
                                       KnowledgeRecallNode knowledgeRecallNode,
                                       RerankNode rerankNode,
                                       AnswerComposeNode answerComposeNode,
                                       AnswerVerifyNode answerVerifyNode,
                                       RetryOrFallbackNode retryOrFallbackNode,
                                       ChatOnlyNode chatOnlyNode,
                                       TerminalNode terminalNode) throws Exception {

        StateGraph workflow = new StateGraph(keyStrategyFactory())
                .addNode(QaState.INTENT_ROUTE.name(), node_async(intentRouteNode))
                .addNode(QaState.QUERY_REWRITE.name(), node_async(queryRewriteNode))
                .addNode(QaState.KNOWLEDGE_RECALL.name(), node_async(knowledgeRecallNode))
                .addNode(QaState.RERANK.name(), node_async(rerankNode))
                .addNode(QaState.ANSWER_COMPOSE.name(), node_async(answerComposeNode))
                .addNode(QaState.ANSWER_VERIFY.name(), node_async(answerVerifyNode))
                .addNode(QaState.RETRY_FALLBACK.name(), node_async(retryOrFallbackNode))
                .addNode(QaState.CHAT_ONLY.name(), node_async(chatOnlyNode))
                // graph-core 校验条件边目标必须为已注册节点，故终结目标统一走 TERMINAL → END
                .addNode(QaState.TERMINAL.name(), node_async(terminalNode));

        workflow.addEdge(START, QaState.INTENT_ROUTE.name());

        // 意图路由：业务 → 改写；闲聊 → 兜底
        workflow.addConditionalEdges(QaState.INTENT_ROUTE.name(),
                edge_async(state -> state.value(QaContextKey.NEXT).map(String::valueOf)
                        .orElse(QaState.CHAT_ONLY.name())),
                Map.of(QaState.QUERY_REWRITE.name(), QaState.QUERY_REWRITE.name(),
                        QaState.CHAT_ONLY.name(), QaState.CHAT_ONLY.name()));

        workflow.addEdge(QaState.QUERY_REWRITE.name(), QaState.KNOWLEDGE_RECALL.name());
        workflow.addEdge(QaState.KNOWLEDGE_RECALL.name(), QaState.RERANK.name());
        workflow.addEdge(QaState.RERANK.name(), QaState.ANSWER_COMPOSE.name());
        workflow.addEdge(QaState.ANSWER_COMPOSE.name(), QaState.ANSWER_VERIFY.name());
        workflow.addEdge(QaState.ANSWER_VERIFY.name(), QaState.RETRY_FALLBACK.name());

        // 重试与兜底：可回 QUERY_REWRITE 重试，或终结
        workflow.addConditionalEdges(QaState.RETRY_FALLBACK.name(),
                edge_async(state -> state.value(QaContextKey.NEXT).map(String::valueOf)
                        .orElse(QaState.TERMINAL.name())),
                Map.of(QaState.QUERY_REWRITE.name(), QaState.QUERY_REWRITE.name(),
                        QaState.TERMINAL.name(), QaState.TERMINAL.name()));

        // 闲聊兜底终态
        workflow.addConditionalEdges(QaState.CHAT_ONLY.name(),
                edge_async(state -> QaState.TERMINAL.name()),
                Map.of(QaState.TERMINAL.name(), QaState.TERMINAL.name()));

        workflow.addEdge(QaState.TERMINAL.name(), END);

        return workflow.compile();
    }

    /**
     * 执行一次问答，返回最终状态。
     * 创建 OTel 根 span（一次问答 = 一个 trace），各节点 span 自动成为其子 span；
     * 汇总本次问答全部 LLM/向量调用的 token（按模型分组）写入根 span。
     */
    public OverAllState run(QaContext.QaInput input) {
        guard.acquire();
        Span root = null;
        try {
            root = qaTracing.begin("chat/ask");
            root.setAttribute("kb.id", input.kbId());
            root.setAttribute("kb.name", input.kbName());
            root.setAttribute("session.id", input.sessionId());
            root.setAttribute("question", input.question());
            root.setAttribute("langfuse.trace.name", "问答: " + Texts.truncate(input.question(), 50));
            root.setAttribute("langfuse.span.type", "TASK");
            TokenAccumulator.begin();
            long start = System.currentTimeMillis();
            OverAllState result = null;
            try (Scope scope = root.makeCurrent()) {
                result = doRun(input);
                return result;
            } catch (Exception e) {
                root.recordException(e);
                throw e;
            } finally {
                long[] tokens = TokenAccumulator.totals();
                TokenAccumulator.flushToSpan(root);
                root.end();
                logQaSummary(input, result, tokens, System.currentTimeMillis() - start);
            }
        } finally {
            guard.release();
        }
    }

    /** 一次问答的汇总体检日志：耗时/意图/证据池/重试/faithfulness/token（配合 MDC 的 requestId 定位整条链路） */
    private void logQaSummary(QaContext.QaInput input, OverAllState state, long[] tokens, long costMs) {
        String intent = null;
        String answer = null;
        int accumulated = 0;
        int retry = 0;
        double faithfulness = -1.0;
        int unsupported = 0;
        int contradicted = 0;
        if (state != null) {
            intent = state.value(QaContextKey.INTENT).map(String::valueOf).orElse(null);
            answer = state.value(QaContextKey.ANSWER).map(String::valueOf).orElse(null);
            Object acc = state.value(QaContextKey.ACCUMULATED_CHUNKS).orElse(null);
            if (acc instanceof List<?> list) {
                accumulated = list.size();
            }
            Object retryV = state.value(QaContextKey.RETRY_COUNT).orElse(null);
            if (retryV instanceof Number n) {
                retry = n.intValue();
            }
            Object faithV = state.value(QaContextKey.FAITHFULNESS_SCORE).orElse(null);
            if (faithV instanceof Number n) {
                faithfulness = n.doubleValue();
            }
            Object unsupV = state.value(QaContextKey.UNSUPPORTED_CLAIMS).orElse(null);
            if (unsupV instanceof List<?> list) {
                unsupported = list.size();
            }
            Object contraV = state.value(QaContextKey.CONTRADICTED_CLAIMS).orElse(null);
            if (contraV instanceof List<?> list) {
                contradicted = list.size();
            }
        }
        log.info("问答汇总 sessionId={} kbId={} 耗时={}ms 意图={} 证据池={} 重试={} faithfulness={} 无支撑={} 矛盾={} token(in/out/total)={}/{}/{} 答案字符={}",
                input.sessionId(), input.kbId(), costMs, intent, accumulated, retry,
                faithfulness < 0 ? "-" : String.format("%.2f", faithfulness), unsupported, contradicted,
                tokens[0], tokens[1], tokens[2],
                answer == null ? 0 : answer.length());
    }

    private OverAllState doRun(QaContext.QaInput input) {
        Map<String, Object> initialState = new HashMap<>();
        initialState.put(QaContextKey.RAW_QUESTION, input.question());
        initialState.put(QaContextKey.KB_ID, input.kbId());
        initialState.put(QaContextKey.KB_NAME, input.kbName());
        initialState.put(QaContextKey.WORKSPACE_ID, input.workspaceId());
        initialState.put(QaContextKey.SESSION_ID, input.sessionId());
        initialState.put(QaContextKey.HISTORY, input.history());
        initialState.put(QaContextKey.RETRY_COUNT, 0);
        initialState.put(QaContextKey.MAX_RETRY, input.maxRetry());
        initialState.put(QaContextKey.AGENT, input.agent());
        initialState.put(QaContextKey.NEXT, QaState.QUERY_REWRITE.name());

        // 每次问答使用唯一 threadId，避免 graph-core 按 threadId 缓存的上次状态污染本次执行
        RunnableConfig config = RunnableConfig.builder()
                .threadId("qa-" + input.sessionId() + "-" + System.nanoTime())
                .build();
        return compiledGraph.invoke(initialState, config)
                .orElseThrow(() -> new BizException("问答流程执行失败"));
    }

    private static KeyStrategyFactory keyStrategyFactory() {
        return () -> {
            Map<String, KeyStrategy> strategies = new HashMap<>();
            for (String key : List.of(
                    QaContextKey.RAW_QUESTION, QaContextKey.KB_ID, QaContextKey.KB_NAME,
                    QaContextKey.SESSION_ID, QaContextKey.INTENT, QaContextKey.QUERIES,
                    QaContextKey.CHUNKS, QaContextKey.ACCUMULATED_CHUNKS,
                    QaContextKey.ANSWER, QaContextKey.REFS,
                    QaContextKey.VERIFY_SCORE, QaContextKey.FAITHFULNESS_SCORE,
                    QaContextKey.UNSUPPORTED_CLAIMS, QaContextKey.CONTRADICTED_CLAIMS,
                    QaContextKey.MISSING_INFO,
                    QaContextKey.RETRY_COUNT, QaContextKey.MAX_RETRY,
                    QaContextKey.NEXT, QaContextKey.HISTORY, QaContextKey.CHAT_ONLY_ANSWER,
                    QaContextKey.AGENT)) {
                strategies.put(key, new ReplaceStrategy());
            }
            return strategies;
        };
    }
}
