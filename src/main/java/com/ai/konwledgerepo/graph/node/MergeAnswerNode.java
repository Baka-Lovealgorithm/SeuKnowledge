package com.ai.konwledgerepo.graph.node;

import com.ai.konwledgerepo.common.PromptCatalog;
import com.ai.konwledgerepo.common.SseStreamContext;
import com.ai.konwledgerepo.entity.ModelUsage;
import com.ai.konwledgerepo.graph.QaContext;
import com.ai.konwledgerepo.graph.QaContextKey;
import com.ai.konwledgerepo.graph.QaState;
import com.ai.konwledgerepo.model.ModelFactory;
import com.ai.konwledgerepo.service.chat.HistoryEntry;
import com.ai.konwledgerepo.tracing.LlmTrace;
import com.ai.konwledgerepo.tracing.QaTracing;
import com.alibaba.cloud.ai.graph.OverAllState;
import io.opentelemetry.api.trace.Span;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 答案合并节点（多意图收口）：业务链路答案 + 闲聊回复 + 注入拒答统一合并输出。
 * <p>分支：
 * <ul>
 *   <li>无闲聊片段（纯业务 / 纯注入）→ 透传，不调用 LLM（业务答案已完整，注入拒答在 CHAT_ONLY_ANSWER）</li>
 *   <li>有闲聊片段但无业务回答（纯闲聊路径，CHAT_ONLY 已产出回复）→ 透传闲聊回复</li>
 *   <li>混合（业务 + 闲聊）→ 用 chitchat（GENERATE）模型生成闲聊回复，再 LLM 合并；
 *       合并输出须【整体包含业务回答原文】且【引用编号集合完全一致】，否则回退结构化拼接（闲聊回复 + 业务回答），
 *       保证知识库答案与引用编号永不损坏。</li>
 * </ul>
 */
@Component
public class MergeAnswerNode extends QaNodeSupport {

    private static final Logger log = LoggerFactory.getLogger(MergeAnswerNode.class);
    private static final Pattern CITATION_PATTERN = Pattern.compile("\\[(\\d{1,3})]");

    private final ModelFactory modelFactory;
    private final PromptCatalog promptCatalog;

    public MergeAnswerNode(ModelFactory modelFactory, QaTracing qaTracing, PromptCatalog promptCatalog) {
        super(qaTracing);
        this.modelFactory = modelFactory;
        this.promptCatalog = promptCatalog;
    }

    @Override
    protected String spanName() {
        return "node/merge_answer";
    }

    @Override
    protected Map<String, Object> applyInternal(OverAllState state, Span span) throws Exception {
        SseStreamContext.sendStage("MERGE_ANSWER", "答案整合");
        String businessAnswer = state.value(QaContextKey.ANSWER).map(String::valueOf).orElse("");
        String chatOnlyAnswer = state.value(QaContextKey.CHAT_ONLY_ANSWER).map(String::valueOf).orElse("");
        List<String> chitchatFragments = QaContext.stringList(state, QaContextKey.CHITCHAT_FRAGMENTS);

        // 分支 1：无闲聊片段（纯业务 / 纯注入）→ 透传，不调用 LLM
        if (chitchatFragments.isEmpty()) {
            span.setAttribute("merge_action", "passthrough");
            span.setAttribute("parts", 1);
            return Map.of(QaContextKey.NEXT, QaState.TERMINAL.name());
        }

        // 分支 2：有闲聊片段但无业务回答（纯闲聊路径，CHAT_ONLY 已产出回复）→ 透传闲聊回复
        if (businessAnswer.isBlank()) {
            span.setAttribute("merge_action", "chat_only");
            span.setAttribute("parts", 1);
            return Map.of(
                    QaContextKey.ANSWER, chatOnlyAnswer,
                    QaContextKey.REFS, "[]",
                    QaContextKey.NEXT, QaState.TERMINAL.name());
        }

        // 分支 3：混合（业务 + 闲聊）→ 生成闲聊回复 + LLM 合并（引用保护 + 结构化兜底）
        span.setAttribute("merge_action", "merge");
        span.setAttribute("parts", 2);
        span.setAttribute("chitchat_fragments", chitchatFragments.size());
        Long workspaceId = QaContext.longValue(state, QaContextKey.WORKSPACE_ID, -1L);
        ChatModel chat = modelFactory.getChatModelByUsage(ModelUsage.GENERATE.value(), workspaceId);

        String chitchatReply = generateChitchatReply(state, chat, chitchatFragments);
        String merged = merge(chat, chitchatReply, businessAnswer);
        String finalAnswer;
        if (merged != null && mergeVerified(businessAnswer, merged)) {
            finalAnswer = merged;
            span.setAttribute("merge_mode", "llm");
        } else {
            // 结构化兜底：闲聊回复 + 业务答案原文，引用零损坏
            finalAnswer = chitchatReply.isBlank() ? businessAnswer : chitchatReply + "\n\n" + businessAnswer;
            span.setAttribute("merge_mode", "structural");
            if (merged != null) {
                log.warn("MergeAnswer 合并输出未通过原文/引用校验，回退结构化拼接: businessLen={} mergedLen={}",
                        businessAnswer.length(), merged.length());
            }
        }
        span.setAttribute("answer_length", finalAnswer.length());
        return Map.of(QaContextKey.ANSWER, finalAnswer, QaContextKey.NEXT, QaState.TERMINAL.name());
    }

    /** 闲聊回复生成：逐片段用 chat-only 模板（GENERATE/chitchat 模型），带最近对话与摘要上下文，失败返回空串 */
    private String generateChitchatReply(OverAllState state, ChatModel chat, List<String> fragments) {
        String question = String.join("\n", fragments);
        List<HistoryEntry> history = QaContext.history(state);
        String recentJson = QaContext.renderRecentJson(history, 3);
        if (recentJson.isBlank() || "[]".equals(recentJson)) {
            recentJson = "（无）";
        }
        String memorySummary = state.value(QaContextKey.MEMORY_SUMMARY).map(String::valueOf).orElse("");
        String summaryText = memorySummary.isBlank() ? "（无）" : memorySummary;
        String prompt = promptCatalog.render("chat-only", Map.of(
                "question", question, "recentJson", recentJson, "summaryText", summaryText));
        try {
            return LlmTrace.call(qaTracing, chat, prompt);
        } catch (Exception e) {
            log.warn("MergeAnswer 闲聊回复生成失败，合并时跳过闲聊部分: {}", e.getMessage());
            return "";
        }
    }

    /** LLM 合并：merge-answer 模板（chitchat 模型），失败/空返回 null（触发结构化兜底） */
    private String merge(ChatModel chat, String chitchatReply, String businessAnswer) {
        try {
            String prompt = promptCatalog.render("merge-answer", Map.of(
                    "chitchatReply", chitchatReply, "businessAnswer", businessAnswer));
            String out = LlmTrace.call(qaTracing, chat, prompt);
            return out == null || out.isBlank() ? null : out.trim();
        } catch (Exception e) {
            log.warn("MergeAnswer 合并调用失败，回退结构化拼接: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 合并质量校验：合并输出必须【整体包含业务回答原文】且【引用编号集合与业务回答完全一致】
     * （业务答案未被改写、引用未缺失未新增）。不满足则判定合并不可信，回退结构化拼接。
     */
    private static boolean mergeVerified(String businessAnswer, String merged) {
        if (merged == null || !merged.contains(businessAnswer)) {
            return false;
        }
        return citationSet(businessAnswer).equals(citationSet(merged));
    }

    private static Set<String> citationSet(String text) {
        Set<String> set = new HashSet<>();
        if (text == null) {
            return set;
        }
        Matcher m = CITATION_PATTERN.matcher(text);
        while (m.find()) {
            set.add(m.group(1));
        }
        return set;
    }
}
