package com.ai.konwledgerepo.graph.node;

import com.ai.konwledgerepo.common.Defaults;
import com.ai.konwledgerepo.common.PromptCatalog;
import com.ai.konwledgerepo.graph.AgentConfig;
import com.ai.konwledgerepo.graph.ChunkEvidence;
import com.ai.konwledgerepo.graph.CitationValidator;
import com.ai.konwledgerepo.graph.EvidenceFormatter;
import com.ai.konwledgerepo.graph.QaContext;
import com.ai.konwledgerepo.graph.QaContextKey;
import com.ai.konwledgerepo.graph.QaState;
import com.ai.konwledgerepo.model.ModelFactory;
import com.ai.konwledgerepo.common.SseStreamContext;
import com.ai.konwledgerepo.tracing.LlmTrace;
import com.ai.konwledgerepo.tracing.QaTracing;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.trace.Span;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

/**
 * 答案生成节点：仅依据证据回答，输出带引用（[1][2]）的答案与 refs。
 */
@Component
public class AnswerComposeNode implements NodeAction {

    private static final Logger log = LoggerFactory.getLogger(AnswerComposeNode.class);

    private final ModelFactory modelFactory;
    private final ObjectMapper objectMapper;
    private final QaTracing qaTracing;
    private final PromptCatalog promptCatalog;

    public AnswerComposeNode(ModelFactory modelFactory, ObjectMapper objectMapper, QaTracing qaTracing,
                             PromptCatalog promptCatalog) {
        this.modelFactory = modelFactory;
        this.objectMapper = objectMapper;
        this.qaTracing = qaTracing;
        this.promptCatalog = promptCatalog;
    }

    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {
        SseStreamContext.sendStage("ANSWER_COMPOSE", "答案生成");
        Span span = qaTracing.begin("node/answer_compose");
        try {
            String question = state.value(QaContextKey.RAW_QUESTION).map(String::valueOf).orElse("");
            // 本轮新选中（Rerank 输出）
            List<ChunkEvidence> current = QaContext.chunks(state.value(QaContextKey.CHUNKS).orElse(List.of()));
            // 累计证据池：历轮被 rerank 选中、已保留的知识（重试轮不再参与 rerank，但最终一并交 AI）
            List<ChunkEvidence> accumulated = QaContext.chunks(
                    state.value(QaContextKey.ACCUMULATED_CHUNKS).orElse(List.of()));
            // 合并：累计池 ∪ 本轮新选中（按 sourceType:chunkId 去重），保证首次精选知识不丢失
            List<ChunkEvidence> chunks = QaContext.mergeEvidence(accumulated, current);
            if (chunks.size() > current.size()) {
                span.setAttribute("accumulated_evidence", chunks.size() - current.size());
            }

            if (chunks.isEmpty()) {
                span.setAttribute("refs_count", 0);
                return Map.of(
                        QaContextKey.ANSWER, Defaults.NO_EVIDENCE_ANSWER,
                        QaContextKey.REFS, "[]",
                        QaContextKey.NEXT, QaState.ANSWER_VERIFY.name());
            }

            String evidenceJson = EvidenceFormatter.toEvidenceJson(chunks);

            AgentConfig agent = QaContext.agent(state);
            String agentPrompt = (agent == null || agent.systemPrompt() == null || agent.systemPrompt().isBlank())
                    ? ""
                    : "Agent 设定：" + agent.systemPrompt() + "\n";

            Long workspaceId = QaContext.longValue(state, QaContextKey.WORKSPACE_ID, -1L);
            ChatModel chat = modelFactory.getChatModelByUsage("GENERATE", workspaceId);

            String rules = promptCatalog.get("answer-compose-rules").formatted(agentPrompt);
            String input = promptCatalog.get("answer-compose-input").formatted(evidenceJson, question);
            List<Message> messages = List.of(new SystemMessage(rules), new UserMessage(input));

            String answer = generateAnswer(state, chat, messages);
            // 引用编号程序化校验：移除越界 [n]（保证答案与 REFS 一致；流式 delta 已发出无法撤回，仅修正存储答案）
            if (CitationValidator.hasOutOfRange(answer, chunks.size())) {
                span.setAttribute("citation_fixed", true);
                log.warn("AnswerCompose 移除越界引用: maxCitation={} refsCount={} answer={}",
                        CitationValidator.maxCitation(answer), chunks.size(),
                        answer.length() > 120 ? answer.substring(0, 120) + "..." : answer);
                answer = CitationValidator.stripOutOfRange(answer, chunks.size());
            }
            span.setAttribute("refs_count", chunks.size());
            span.setAttribute("answer_length", answer == null ? 0 : answer.length());
            span.setAttribute("retry", QaContext.intValue(state, QaContextKey.RETRY_COUNT, 0));
            return Map.of(
                    QaContextKey.ANSWER, answer,
                    QaContextKey.REFS, QaContext.toRefsJson(chunks, objectMapper),
                    // 写回合并后的完整证据（累计池∪本轮），供 AnswerVerify 基于完整证据评估
                    QaContextKey.CHUNKS, chunks,
                    QaContextKey.NEXT, QaState.ANSWER_VERIFY.name());
        } catch (Exception e) {
            span.recordException(e);
            throw e;
        } finally {
            span.end();
        }
    }

    /** 有 SSE 上下文且为首轮时流式输出答案 token；重试轮次或非流式一次性生成（避免重试重复输出） */
    private String generateAnswer(OverAllState state, ChatModel chat, List<Message> messages) {
        SseEmitter emitter = SseStreamContext.get();
        int retry = QaContext.intValue(state, QaContextKey.RETRY_COUNT, 0);
        if (emitter == null || retry > 0) {
            return LlmTrace.call(qaTracing, chat, messages);
        }
        // 流式回调可能运行在模型供应商/Reactor 线程（ThreadLocal 不可见），
        // 必须使用捕获的 emitter 显式推送（见 SseStreamContext.send(emitter, ...)）
        String answer = LlmTrace.stream(qaTracing, chat, messages, text -> SseStreamContext.send(emitter, "delta", text));
        SseStreamContext.markDeltaSent();
        return answer;
    }
}
