package com.ai.konwledgerepo.graph.node;

import com.ai.konwledgerepo.common.PromptCatalog;
import com.ai.konwledgerepo.entity.ModelUsage;
import com.ai.konwledgerepo.graph.AgentConfig;
import com.ai.konwledgerepo.graph.ChunkEvidence;
import com.ai.konwledgerepo.graph.QaContext;
import com.ai.konwledgerepo.graph.QaContextKey;
import com.ai.konwledgerepo.graph.QaState;
import com.ai.konwledgerepo.model.ModelFactory;
import com.ai.konwledgerepo.common.SseStreamContext;
import com.ai.konwledgerepo.service.chat.HistoryEntry;
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

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 问题改写节点：结合会话上下文改写/补全/消歧，生成 1~N 个候选查询。
 * 重试轮自动扩大检索范围：若上一轮自检输出了"缺失信息"反馈（MISSING_INFO），
 * 则针对缺失项定向改写（附已覆盖内容避免重复检索）；否则回退通用宽泛提示。
 */
@Component
public class QueryRewriteNode implements NodeAction {

    private static final Logger log = LoggerFactory.getLogger(QueryRewriteNode.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ModelFactory modelFactory;
    private final QaTracing qaTracing;
    private final PromptCatalog promptCatalog;

    public QueryRewriteNode(ModelFactory modelFactory, QaTracing qaTracing, PromptCatalog promptCatalog) {
        this.modelFactory = modelFactory;
        this.qaTracing = qaTracing;
        this.promptCatalog = promptCatalog;
    }

    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {
        SseStreamContext.throwIfCancelled();
        int retry = QaContext.intValue(state, QaContextKey.RETRY_COUNT, 0);
        SseStreamContext.sendStage("QUERY_REWRITE",
                retry > 0 ? "问题改写（第 " + retry + " 次重试）" : "问题改写");
        Span span = qaTracing.begin("node/query_rewrite");
        try {
            String rawQuestion = state.value(QaContextKey.RAW_QUESTION)
                    .map(String::valueOf).orElse("");
            List<HistoryEntry> history = QaContext.history(state);

            String historyJson = renderHistory(history);
            String retryHint = buildRetryHint(state, retry);
            AgentConfig agent = QaContext.agent(state);
            String agentPrompt = (agent == null || agent.systemPrompt() == null || agent.systemPrompt().isBlank())
                    ? ""
                    : "Agent 场景设定：" + agent.systemPrompt() + "\n";

            Long workspaceId = QaContext.longValue(state, QaContextKey.WORKSPACE_ID, -1L);
            ChatModel chat = modelFactory.getChatModelByUsage(ModelUsage.ROUTER.value(), workspaceId);
            String rules = promptCatalog.get("query-rewrite-rules").formatted(agentPrompt);
            String input = promptCatalog.get("query-rewrite-input").formatted(historyJson, rawQuestion, retryHint);
            List<Message> messages = List.of(new SystemMessage(rules), new UserMessage(input));

            String response = LlmTrace.call(qaTracing, chat, messages);
            List<String> queries = parseQueries(response, rawQuestion);
            span.setAttribute("queries", queries.size());
            span.setAttribute("retry", retry);
            if (retry > 0 && !retryHint.isBlank()) {
                log.info("QueryRewrite 第 {} 次重试：{}（改写为 {} 个查询）", retry, retryHint, queries.size());
                log.info("QueryRewrite 实际查询：{}", queries);
            }
            return Map.of(
                    QaContextKey.QUERIES, queries,
                    QaContextKey.NEXT, QaState.KNOWLEDGE_RECALL.name());
        } catch (Exception e) {
            span.recordException(e);
            throw e;
        } finally {
            span.end();
        }
    }

    /**
     * 构造重试提示：
     * - 有缺失项反馈 → 定向改写（附上一轮已覆盖内容，避免重复检索）
     * - 无缺失项反馈 → 回退通用"更宽泛关键词"提示
     */
    private static String buildRetryHint(OverAllState state, int retry) {
        if (retry <= 0) {
            return "";
        }
        String missing = state.value(QaContextKey.MISSING_INFO).map(String::valueOf).orElse("").trim();
        if (missing.isEmpty()) {
            return "\n（上次检索证据不足，请用更宽泛的关键词改写查询，扩大检索范围）";
        }
        // 上一轮证据标题（QueryRewrite 先于 KnowledgeRecall 运行，state 中 CHUNKS 仍为上一轮精排输出）
        List<ChunkEvidence> prevChunks = QaContext.chunks(state.value(QaContextKey.CHUNKS).orElse(List.of()));
        Set<String> titles = new LinkedHashSet<>();
        for (ChunkEvidence c : prevChunks) {
            String t = c.title() == null || c.title().isBlank() ? c.docName() : c.title();
            if (t.length() > 30) {
                t = t.substring(0, 30);
            }
            if (!t.isBlank()) {
                titles.add(t);
            }
            if (titles.size() >= 8) {
                break;
            }
        }
        String covered = titles.isEmpty() ? "无" : String.join("；", titles);
        return "\n（上次检索已覆盖：" + covered + "；仍缺失：" + missing
                + "。请针对缺失的信息定向改写查询，避免重复检索已覆盖内容）";
    }

    /** 将历史记录序列化为 JSON 数组字符串 [{role,content}]，空返回 "[]" */
    private static String renderHistory(List<HistoryEntry> history) {
        if (history == null || history.isEmpty()) {
            return "[]";
        }
        try {
            return MAPPER.writeValueAsString(history);
        } catch (Exception e) {
            log.warn("历史记录 JSON 序列化失败，回退空列表: {}", e.getMessage());
            return "[]";
        }
    }

    private static List<String> parseQueries(String response, String fallback) {
        Set<String> result = new LinkedHashSet<>();
        if (response != null) {
            for (String line : response.split("\\n")) {
                String q = line.replaceAll("^[\\s\\d.、\\-*•]+", "").trim();
                if (!q.isEmpty() && q.length() <= 200) {
                    result.add(q);
                }
                if (result.size() >= 3) {
                    break;
                }
            }
        }
        if (result.isEmpty()) {
            result.add(fallback);
        }
        return new ArrayList<>(result);
    }
}
