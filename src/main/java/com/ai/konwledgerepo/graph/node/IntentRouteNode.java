package com.ai.konwledgerepo.graph.node;

import com.ai.konwledgerepo.common.PromptCatalog;
import com.ai.konwledgerepo.entity.Intent;
import com.ai.konwledgerepo.entity.ModelUsage;
import com.ai.konwledgerepo.graph.AgentConfig;
import com.ai.konwledgerepo.graph.QaContext;
import com.ai.konwledgerepo.graph.QaContextKey;
import com.ai.konwledgerepo.graph.QaState;
import com.ai.konwledgerepo.model.ModelFactory;
import com.ai.konwledgerepo.common.SseStreamContext;
import com.ai.konwledgerepo.tracing.LlmTrace;
import com.ai.konwledgerepo.tracing.QaTracing;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import io.opentelemetry.api.trace.Span;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 意图路由节点：判定用户问题是业务咨询还是闲聊。
 * 业务 → QUERY_REWRITE；闲聊/非业务 → CHAT_ONLY 兜底，不进检索链路。
 */
@Component
public class IntentRouteNode implements NodeAction {

    private final ModelFactory modelFactory;
    private final QaTracing qaTracing;
    private final PromptCatalog promptCatalog;

    public IntentRouteNode(ModelFactory modelFactory, QaTracing qaTracing, PromptCatalog promptCatalog) {
        this.modelFactory = modelFactory;
        this.qaTracing = qaTracing;
        this.promptCatalog = promptCatalog;
    }

    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {
        SseStreamContext.sendStage("INTENT_ROUTE", "意图分析");
        Span span = qaTracing.begin("node/intent_route");
        try {
            String question = state.value(QaContextKey.RAW_QUESTION)
                    .map(String::valueOf).orElse("");
            String kbName = state.value(QaContextKey.KB_NAME)
                    .map(String::valueOf).orElse("本知识库");
            AgentConfig agent = QaContext.agent(state);
            String agentPrompt = (agent == null || agent.systemPrompt() == null || agent.systemPrompt().isBlank())
                    ? ""
                    : "Agent 设定：" + agent.systemPrompt() + "\n";

            Long workspaceId = QaContext.longValue(state, QaContextKey.WORKSPACE_ID, -1L);
            ChatModel chat = modelFactory.getChatModelByUsage(ModelUsage.ROUTER.value(), workspaceId);
            String prompt = promptCatalog.get("intent-route").formatted(kbName, agentPrompt, question);

            String response = LlmTrace.call(qaTracing, chat, prompt);
            boolean business = response != null && response.trim().toUpperCase().contains(Intent.BUSINESS.value());

            String next = business ? QaState.QUERY_REWRITE.name() : QaState.CHAT_ONLY.name();
            span.setAttribute("intent", business ? Intent.BUSINESS.value() : Intent.CHITCHAT.value());
            return Map.of(
                    QaContextKey.INTENT, business ? Intent.BUSINESS.value() : Intent.CHITCHAT.value(),
                    QaContextKey.NEXT, next);
        } catch (Exception e) {
            span.recordException(e);
            throw e;
        } finally {
            span.end();
        }
    }
}
