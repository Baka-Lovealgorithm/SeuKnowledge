package com.ai.konwledgerepo.graph.node;

import com.ai.konwledgerepo.common.PromptCatalog;
import com.ai.konwledgerepo.entity.Intent;
import com.ai.konwledgerepo.entity.ModelConfig;
import com.ai.konwledgerepo.entity.ModelUsage;
import com.ai.konwledgerepo.graph.JudgeOptions;
import com.ai.konwledgerepo.graph.QaContext;
import com.ai.konwledgerepo.graph.QaContextKey;
import com.ai.konwledgerepo.graph.QaState;
import com.ai.konwledgerepo.model.ModelFactory;
import com.ai.konwledgerepo.common.SseStreamContext;
import com.ai.konwledgerepo.service.chat.HistoryEntry;
import com.ai.konwledgerepo.tracing.LlmTrace;
import com.ai.konwledgerepo.tracing.QaTracing;
import com.alibaba.cloud.ai.graph.OverAllState;
import io.opentelemetry.api.trace.Span;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 意图路由节点：判定用户问题是业务咨询还是闲聊。
 * 业务 → QUERY_REWRITE；闲聊 → CHAT_ONLY 兜底，不进检索链路。
 * 注入最近 2 轮对话供省略/指代消歧。
 */
@Component
public class IntentRouteNode extends QaNodeSupport {

    private static final Logger log = LoggerFactory.getLogger(IntentRouteNode.class);
    private static final int ROUTER_MAX_ATTEMPTS = 2;
    private static final int ROUTER_RECENT_ROUNDS = 2;

    private final ModelFactory modelFactory;
    private final PromptCatalog promptCatalog;

    public IntentRouteNode(ModelFactory modelFactory, QaTracing qaTracing, PromptCatalog promptCatalog) {
        super(qaTracing);
        this.modelFactory = modelFactory;
        this.promptCatalog = promptCatalog;
    }

    @Override
    protected String spanName() {
        return "node/intent_route";
    }

    @Override
    protected Map<String, Object> applyInternal(OverAllState state, Span span) throws Exception {
        SseStreamContext.sendStage("INTENT_ROUTE", "意图分析");
        String question = state.value(QaContextKey.RAW_QUESTION).map(String::valueOf).orElse("");
        String kbName = state.value(QaContextKey.KB_NAME).map(String::valueOf).orElse("本知识库");
        String agentPrompt = QaContext.agentPrompt(state);

        Long workspaceId = QaContext.longValue(state, QaContextKey.WORKSPACE_ID, -1L);
        ChatModel chat = modelFactory.getChatModelByUsage(ModelUsage.ROUTER.value(), workspaceId);
        ModelConfig cfg = modelFactory.resolveChatConfig(ModelUsage.ROUTER.value(), workspaceId);
        ChatOptions opts = JudgeOptions.router(chat, cfg);
        List<HistoryEntry> history = QaContext.history(state);
        String recentJson = QaContext.renderRecentJson(history, ROUTER_RECENT_ROUNDS);
        if (recentJson.isBlank() || "[]".equals(recentJson)) {
            recentJson = "（无）";
        }
        String prompt = promptCatalog.get("intent-route").formatted(kbName, agentPrompt, recentJson, question);

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
            intent = Intent.BUSINESS;
            span.setAttribute("router_fallback", true);
            log.warn("意图路由 {} 次输出均无法解析，失败反转默认 BUSINESS: response={}",
                    ROUTER_MAX_ATTEMPTS,
                    response == null ? "<null>" : response.length() > 100 ? response.substring(0, 100) + "…" : response);
        }

        String next = intent == Intent.BUSINESS ? QaState.QUERY_REWRITE.name() : QaState.CHAT_ONLY.name();
        span.setAttribute("intent", intent.value());
        return Map.of(QaContextKey.INTENT, intent.value(), QaContextKey.NEXT, next);
    }

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