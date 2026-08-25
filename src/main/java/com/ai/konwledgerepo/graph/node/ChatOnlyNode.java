package com.ai.konwledgerepo.graph.node;

import com.ai.konwledgerepo.common.PromptCatalog;
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
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

/**
 * 闲聊兜底节点：非业务问题直接友好回复，不进入检索链路。有 SSE 上下文时流式输出。
 */
@Component
public class ChatOnlyNode implements NodeAction {

    private final ModelFactory modelFactory;
    private final QaTracing qaTracing;
    private final PromptCatalog promptCatalog;

    public ChatOnlyNode(ModelFactory modelFactory, QaTracing qaTracing, PromptCatalog promptCatalog) {
        this.modelFactory = modelFactory;
        this.qaTracing = qaTracing;
        this.promptCatalog = promptCatalog;
    }

    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {
        SseStreamContext.sendStage("CHAT_ONLY", "闲聊回复");
        Span span = qaTracing.begin("node/chat_only");
        try {
            String question = state.value(QaContextKey.RAW_QUESTION).map(String::valueOf).orElse("");

            Long workspaceId = QaContext.longValue(state, QaContextKey.WORKSPACE_ID, -1L);
            ChatModel chat = modelFactory.getChatModelByUsage("GENERATE", workspaceId);
            String prompt = promptCatalog.get("chat-only").formatted(question);

            String answer;
            SseEmitter emitter = SseStreamContext.get();
            if (emitter != null) {
                // 流式回调运行在模型供应商/Reactor 线程（ThreadLocal 不可见），用捕获的 emitter 显式推送
                answer = LlmTrace.stream(qaTracing, chat, prompt, text -> SseStreamContext.send(emitter, "delta", text));
                SseStreamContext.markDeltaSent();
            } else {
                answer = LlmTrace.call(qaTracing, chat, prompt);
            }
            span.setAttribute("answer_length", answer == null ? 0 : answer.length());
            return Map.of(
                    QaContextKey.CHAT_ONLY_ANSWER, answer,
                    QaContextKey.NEXT, QaState.TERMINAL.name());
        } catch (Exception e) {
            span.recordException(e);
            throw e;
        } finally {
            span.end();
        }
    }
}
