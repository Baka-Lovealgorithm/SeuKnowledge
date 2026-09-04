package com.ai.konwledgerepo.graph;

import com.ai.konwledgerepo.entity.SourceType;
import com.ai.konwledgerepo.service.chat.HistoryEntry;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 问答链路上下文辅助：状态键常量见 {@link QaContextKey}，此处提供入参与类型安全读取。
 */
public final class QaContext {

    private static final Logger log = LoggerFactory.getLogger(QaContext.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 单次问答的入参（不进状态图存储） */
    public record QaInput(Long kbId, String kbName, Long sessionId, String question,
                          List<HistoryEntry> history, int maxRetry, AgentConfig agent, Long workspaceId,
                          String memorySummary) {
    }

    /** 读取 Agent 配置快照；未注入时返回 null，节点自行回退默认行为 */
    public static AgentConfig agent(OverAllState state) {
        Object value = state.value(QaContextKey.AGENT).orElse(null);
        return value instanceof AgentConfig ac ? ac : null;
    }

    @SuppressWarnings("unchecked")
    public static List<ChunkEvidence> chunks(Object value) {
        return value instanceof List<?> list ? (List<ChunkEvidence>) list : List.of();
    }

    @SuppressWarnings("unchecked")
    public static List<String> stringList(OverAllState state, String key) {
        Object value = state.value(key).orElse(List.of());
        return value instanceof List<?> list ? (List<String>) list : List.of();
    }

    /**
     * 合并多组证据并按 sourceType:chunkId 去重（保留先出现的）。
     * 用于累计证据池（历轮精选保留）与本轮新选中证据的合并；也用于累计池追加。
     */
    public static List<ChunkEvidence> mergeEvidence(List<ChunkEvidence>... groups) {
        List<ChunkEvidence> merged = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (List<ChunkEvidence> group : groups) {
            if (group == null) {
                continue;
            }
            for (ChunkEvidence c : group) {
                String key = SourceType.dedupKey(c.sourceType(), c.chunkId());
                if (seen.add(key)) {
                    merged.add(c);
                }
            }
        }
        return merged;
    }

    /**
     * 业务链路的有效问题：多意图拆分后为业务片段聚合（BUSINESS_QUESTION）；
     * 其次为查询改写产出的消歧后问题（RESOLVED_QUESTION，指代/省略已补全）；
     * 均无时为原始问题。供改写/生成/自检共用，保证业务链路只针对业务片段
     * （避免闲聊/注入部分干扰自检与生成）。
     */
    public static String effectiveQuestion(OverAllState state) {
        return state.value(QaContextKey.BUSINESS_QUESTION)
                .map(String::valueOf)
                .filter(s -> !s.isBlank())
                .or(() -> state.value(QaContextKey.RESOLVED_QUESTION)
                        .map(String::valueOf)
                        .filter(s -> !s.isBlank()))
                .orElseGet(() -> state.value(QaContextKey.RAW_QUESTION).map(String::valueOf).orElse(""));
    }

    /**
     * 消歧前的有效问题（对照问题）：多意图拆分后为业务片段聚合（BUSINESS_QUESTION），否则为原始问题。
     * 即 {@link #effectiveQuestion} 的输入侧，供生成/自检对照原问题、防止改写有损丢失子问题。
     */
    public static String preRewriteQuestion(OverAllState state) {
        return state.value(QaContextKey.BUSINESS_QUESTION)
                .map(String::valueOf)
                .filter(s -> !s.isBlank())
                .orElseGet(() -> state.value(QaContextKey.RAW_QUESTION).map(String::valueOf).orElse(""));
    }

    public static long longValue(OverAllState state, String key, long def) {
        return state.value(key)
                .filter(v -> v instanceof Number)
                .map(v -> ((Number) v).longValue())
                .orElse(def);
    }

    public static int intValue(OverAllState state, String key, int def) {
        return state.value(key)
                .filter(v -> v instanceof Number)
                .map(v -> ((Number) v).intValue())
                .orElse(def);
    }

    public static double doubleValue(OverAllState state, String key, double def) {
        return state.value(key)
                .filter(v -> v instanceof Number)
                .map(v -> ((Number) v).doubleValue())
                .orElse(def);
    }

    public static boolean booleanValue(OverAllState state, String key, boolean def) {
        return state.value(key)
                .filter(v -> v instanceof Boolean)
                .map(v -> (Boolean) v)
                .orElse(def);
    }

    /**
     * 证据列表序列化为引用 JSON 数组字符串（chunkId/docName/page/sourceType/title/docId/content）。
     * 答案生成与拒答兜底共用，保证 REFS 结构一致（前端 refs 卡片渲染）。
     * content 为表格感知截断的 snippet（见 {@link RefSnippet}），供引用卡片离线展示；
     * 历史证据"展开全文"走实时查询（/documents/{id}/chunks 按 chunkId 取当前内容，
     * 见前端 Chat.vue openRefDetail），故不再存全文快照；
     * docId 仅 CHUNK 来源有值（BUSINESS/QA 为 null，前端据此不提供展开详情）。
     */
    public static String toRefsJson(List<ChunkEvidence> chunks, ObjectMapper mapper) throws JsonProcessingException {
        List<Map<String, Object>> refs = new ArrayList<>();
        for (ChunkEvidence c : chunks) {
            Map<String, Object> ref = new HashMap<>();
            ref.put("chunkId", c.chunkId());
            ref.put("docName", c.docName());
            ref.put("page", c.pageNum() == null ? 0 : c.pageNum());
            ref.put("sourceType", SourceType.normalize(c.sourceType()));
            ref.put("title", c.title() == null ? "" : c.title());
            ref.put("docId", c.docId());
            ref.put("content", RefSnippet.snippet(c.content()));
            refs.add(ref);
        }
        return mapper.writeValueAsString(refs);
    }

    @SuppressWarnings("unchecked")
    public static List<HistoryEntry> history(OverAllState state) {
        Object value = state.value(QaContextKey.HISTORY).orElse(List.of());
        return value instanceof List<?> list ? (List<HistoryEntry>) list : List.of();
    }

    /**
     * 统一构造 Agent 系统提示词前缀（供各节点共用）。
     * 无 Agent 或 systemPrompt 为空时返回空字符串。
     */
    public static String agentPrompt(OverAllState state) {
        AgentConfig agent = agent(state);
        if (agent == null || agent.systemPrompt() == null || agent.systemPrompt().isBlank()) {
            return "";
        }
        return "Agent 设定：" + agent.systemPrompt() + "\n";
    }

    /**
     * 取最近 maxRounds 轮对话（以 user 消息计数）序列化为 JSON 数组字符串；空返回 "[]"。
     * 供意图路由 / 问题改写节点注入最近对话上下文（省略、指代消歧）。
     */
    public static String renderRecentJson(List<HistoryEntry> history, int maxRounds) {
        if (history == null || history.isEmpty()) {
            return "[]";
        }
        // 从尾部取到包含 maxRounds 条 user 消息
        int userCount = 0;
        int start = history.size();
        for (int i = history.size() - 1; i >= 0; i--) {
            if ("user".equals(history.get(i).role())) {
                userCount++;
            }
            start = i;
            if (userCount >= maxRounds) {
                break;
            }
        }
        List<HistoryEntry> recent = history.subList(start, history.size());
        try {
            return MAPPER.writeValueAsString(recent);
        } catch (Exception e) {
            log.warn("最近对话 JSON 序列化失败，回退空列表: {}", e.getMessage());
            return "[]";
        }
    }

    private QaContext() {
    }
}
