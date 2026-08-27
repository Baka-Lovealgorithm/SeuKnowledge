package com.ai.konwledgerepo.graph.node;

import com.ai.konwledgerepo.common.PromptCatalog;
import com.ai.konwledgerepo.entity.Intent;
import com.ai.konwledgerepo.entity.ModelConfig;
import com.ai.konwledgerepo.entity.ModelUsage;
import com.ai.konwledgerepo.graph.AgentConfig;
import com.ai.konwledgerepo.graph.JudgeOptions;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 意图路由节点：判定用户问题是业务咨询还是闲聊。
 * 业务 → QUERY_REWRITE；闲聊 → CHAT_ONLY 兜底，不进检索链路。
 * <p>任务硬约束：温度 0 + maxTokens 32，消除抽样方差（同一问题多次判为不同意图）；
 * 失败反转：输出无法解析为 BUSINESS/CHITCHAT 时重试一次，仍不明则默认 BUSINESS，
 * 避免"判为闲聊却静默丢失答案"的非对称失败。
 */
@Component
public class IntentRouteNode implements NodeAction {

    private static final Logger log = LoggerFactory.getLogger(IntentRouteNode.class);
    private static final int ROUTER_MAX_ATTEMPTS = 2;

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
        SseStreamContext.throwIfCancelled();
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
            ModelConfig cfg = modelFactory.resolveChatConfig(ModelUsage.ROUTER.value(), workspaceId);
            ChatOptions opts = JudgeOptions.router(chat, cfg);
            String prompt = promptCatalog.get("intent-route").formatted(kbName, agentPrompt, question);

            // 温度 0 + maxTokens 32 硬约束，最多 2 次尝试；失败反转默认 BUSINESS
            Intent intent = null;
            String response = null;
            for (int attempt = 1; attempt <= ROUTER_MAX_ATTEMPTS; attempt++) {
                response = LlmTrace.call(qaTracing, chat, prompt, opts);
                intent = parseIntent(response);
                if (intent != null) {
                    break;
                }
                if (attempt < ROUTER_MAX_ATTEMPTS) {
                    log.warn("意图路由输出无法解析（第 {} 次），重试: response={}", attempt,
                            response == null ? "<null>" : response.length() > 100 ? response.substring(0, 100) + "…" : response);
                }
            }
            if (intent == null) {
                intent = Intent.BUSINESS; // 失败反转：默认业务
                span.setAttribute("router_fallback", true);
                log.warn("意图路由 {} 次输出均无法解析，失败反转默认 BUSINESS: response={}",
                        ROUTER_MAX_ATTEMPTS,
                        response == null ? "<null>" : response.length() > 100 ? response.substring(0, 100) + "…" : response);
            }

            String next = intent == Intent.BUSINESS ? QaState.QUERY_REWRITE.name() : QaState.CHAT_ONLY.name();
            span.setAttribute("intent", intent.value());
            return Map.of(
                    QaContextKey.INTENT, intent.value(),
                    QaContextKey.NEXT, next);
        } catch (Exception e) {
            span.recordException(e);
            throw e;
        } finally {
            span.end();
        }
    }

    /**
     * 解析路由输出标签。温度 0 + maxTokens 32 下应输出单一单词；
     * 兼容历史含上下文文本（如 "该问题属于 BUSINESS 业务咨询"）。
     *
     * @return 解析到的意图，无法识别时返回 null
     */
    static Intent parseIntent(String response) {
        if (response == null || response.isBlank()) {
            return null;
        }
        String up = response.trim().toUpperCase();
        if (up.contains(Intent.BUSINESS.value())) {
            return Intent.BUSINESS;
        }
        if (up.contains(Intent.CHITCHAT.value())) {
            return Intent.CHITCHAT;
        }
        return null;
    }
}