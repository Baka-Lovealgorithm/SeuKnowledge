package com.ai.konwledgerepo.graph.node;

import com.ai.konwledgerepo.common.PromptCatalog;
import com.ai.konwledgerepo.entity.ModelUsage;
import com.ai.konwledgerepo.graph.QaContext;
import com.ai.konwledgerepo.graph.QaContextKey;
import com.ai.konwledgerepo.graph.QaState;
import com.ai.konwledgerepo.model.ModelFactory;
import com.ai.konwledgerepo.common.SseStreamContext;
import com.ai.konwledgerepo.tracing.LlmTrace;
import com.ai.konwledgerepo.tracing.QaTracing;
import com.alibaba.cloud.ai.graph.OverAllState;
import io.opentelemetry.api.trace.Span;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

/**
 * 闲聊兜底节点：非业务问题直接友好回复，不进入检索链路。有 SSE 上下文时流式输出。
 */
@Component
public class ChatOnlyNode extends QaNodeSupport {

    private final ModelFactory modelFactory;
    private final PromptCatalog promptCatalog;

    public ChatOnlyNode(ModelFactory modelFactory, QaTracing qaTracing, PromptCatalog promptCatalog) {
        super(qaTracing);
        this.modelFactory = modelFactory;
        this.promptCatalog = promptCatalog;
    }

    @Override
    protected String spanName() {
        return "node/chat_only";
    }

    @Override
    protected Map<String, Object> applyInternal(OverAllState state, Span span) throws Exception {
        SseStreamContext.sendStage("CHAT_ONLY", "闲聊回复");
        // 纯提示注入（无业务内容）：不调用 LLM，直接固定拒答，杜绝模型被注入面
        if (QaContext.booleanValue(state, QaContextKey.INJECTION, false)) {
            span.setAttribute("injection_refused", true);
            return Map.of(QaContextKey.CHAT_ONLY_ANSWER, com.ai.konwledgerepo.common.Defaults.PROMPT_INJECTION_REFUSAL,
                    QaContextKey.NEXT, QaState.MERGE_ANSWER.name());
        }
        String question = state.value(QaContextKey.RAW_QUESTION).map(String::valueOf).orElse("");
        Long workspaceId = QaContext.longValue(state, QaContextKey.WORKSPACE_ID, -1L);
        ChatModel chat = modelFactory.getChatModelByUsage(ModelUsage.GENERATE.value(), workspaceId);
        String prompt = promptCatalog.get("chat-only").formatted(question);
        String answer;
        SseEmitter emitter = SseStreamContext.get();
        java.util.concurrent.atomic.AtomicBoolean cancelled = SseStreamContext.cancelFlag();
        java.util.function.BooleanSupplier cancelSupplier = cancelled == null ? null : cancelled::get;
        if (emitter != null) {
            answer = LlmTrace.stream(qaTracing, chat, prompt,
                    text -> SseStreamContext.send(emitter, "delta", text), cancelSupplier);
            SseStreamContext.markDeltaSent();
        } else {
            answer = LlmTrace.call(qaTracing, chat, prompt);
        }
        span.setAttribute("answer_length", answer == null ? 0 : answer.length());
        return Map.of(QaContextKey.CHAT_ONLY_ANSWER, answer, QaContextKey.NEXT, QaState.MERGE_ANSWER.name());
    }
}
