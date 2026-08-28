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
     * 证据列表序列化为引用 JSON 数组字符串（chunkId/docName/page/sourceType/title）。
     * 答案生成与拒答兜底共用，保证 REFS 结构一致（前端 refs 卡片渲染）。
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
