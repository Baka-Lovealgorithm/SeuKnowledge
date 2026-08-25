package com.ai.konwledgerepo.graph.node;

import com.ai.konwledgerepo.common.Defaults;
import com.ai.konwledgerepo.graph.AgentConfig;
import com.ai.konwledgerepo.graph.ChunkEvidence;
import com.ai.konwledgerepo.graph.QaContextKey;
import com.ai.konwledgerepo.graph.QaState;
import com.ai.konwledgerepo.tracing.QaTracing;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 重试与兜底节点测试：无证据 → 兜底文案 + 字面量 "TERMINAL"；
 * 分数低于阈值且未达重试上限 → 回 QUERY_REWRITE 且 RETRY_COUNT+1；
 * 分数低于阈值且重试已用尽 → 显式拒答 + REFS 携带候选证据；
 * 分数达标（含重试用尽）→ 输出最终答案。
 */
class RetryOrFallbackNodeTest {

    private QaTracing qaTracing;
    private RetryOrFallbackNode node;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        qaTracing = QaTracing.disabled();
        objectMapper = new ObjectMapper();
        node = new RetryOrFallbackNode(qaTracing, objectMapper);
    }

    private ChunkEvidence ev() {
        return new ChunkEvidence(1L, 100L, 1L, "doc1", 1, "CHUNK", "标题1", "内容1", 0.9);
    }

    private OverAllState state(List<ChunkEvidence> chunks, Double score, Integer retry, Integer maxRetry) {
        Map<String, Object> data = new HashMap<>();
        data.put(QaContextKey.CHUNKS, chunks);
        if (score != null) {
            data.put(QaContextKey.VERIFY_SCORE, score);
        }
        if (retry != null) {
            data.put(QaContextKey.RETRY_COUNT, retry);
        }
        if (maxRetry != null) {
            data.put(QaContextKey.MAX_RETRY, maxRetry);
        }
        return new OverAllState(data);
    }

    @Test
    void noEvidence_returnsFallbackAndTerminal() throws Exception {
        Map<String, Object> out = node.apply(state(List.of(), null, null, null));

        assertEquals(Defaults.INSUFFICIENT_EVIDENCE_ANSWER, out.get(QaContextKey.ANSWER));
        assertEquals("[]", out.get(QaContextKey.REFS));
        assertEquals("TERMINAL", out.get(QaContextKey.NEXT), "节点源码使用字面量 TERMINAL");
    }

    @Test
    void lowScoreWithRetryBudget_routesBackToQueryRewrite() throws Exception {
        Map<String, Object> out = node.apply(state(List.of(ev()), 0.5, 0, 2));

        assertEquals(1, out.get(QaContextKey.RETRY_COUNT), "RETRY_COUNT 应 +1");
        assertEquals(QaState.QUERY_REWRITE.name(), out.get(QaContextKey.NEXT));
    }

    @Test
    void highScore_answersWithTerminal() throws Exception {
        Map<String, Object> out = node.apply(state(List.of(ev()), 0.9, 0, 2));

        assertEquals("TERMINAL", out.get(QaContextKey.NEXT));
        assertFalse(out.containsKey(QaContextKey.ANSWER), "有证据且达标时不再写兜底文案");
    }

    @Test
    void retryExhausted_lowScore_returnsRefusalWithCandidateRefs() throws Exception {
        Map<String, Object> out = node.apply(state(List.of(ev()), 0.5, 2, 2));

        assertEquals(Defaults.INSUFFICIENT_EVIDENCE_REFUSAL, out.get(QaContextKey.ANSWER),
                "重试耗尽仍低分应显式拒答，不再原样输出低分答案");
        assertEquals("TERMINAL", out.get(QaContextKey.NEXT));

        String refs = (String) out.get(QaContextKey.REFS);
        JsonNode refsNode = objectMapper.readTree(refs);
        assertTrue(refsNode.isArray(), "拒答时 REFS 应为候选证据 JSON 数组");
        assertEquals(1, refsNode.size(), "候选证据应包含本轮证据");
        assertEquals(1L, refsNode.get(0).get("chunkId").asLong());
        assertEquals("doc1", refsNode.get(0).get("docName").asText());
        assertEquals(1, refsNode.get(0).get("page").asInt());
        assertEquals("CHUNK", refsNode.get(0).get("sourceType").asText());
    }

    @Test
    void retryExhausted_highScore_stillAnswers() throws Exception {
        Map<String, Object> out = node.apply(state(List.of(ev()), 0.9, 2, 2));

        assertEquals("TERMINAL", out.get(QaContextKey.NEXT), "重试耗尽但分数达标仍正常输出");
        assertFalse(out.containsKey(QaContextKey.ANSWER), "达标时不应写拒答文案");
    }

    @Test
    void agentThresholdOverride_applied() throws Exception {
        AgentConfig agent = new AgentConfig("测试Agent", "系统提示", 5, 0.5, 3, 5);
        Map<String, Object> data = new HashMap<>();
        data.put(QaContextKey.CHUNKS, List.of(ev()));
        data.put(QaContextKey.VERIFY_SCORE, 0.5);
        data.put(QaContextKey.RETRY_COUNT, 0);
        data.put(QaContextKey.MAX_RETRY, 2);
        data.put(QaContextKey.AGENT, agent);

        Map<String, Object> out = node.apply(new OverAllState(data));

        assertEquals("TERMINAL", out.get(QaContextKey.NEXT), "Agent 阈值 0.5 时 0.5 分不再触发重试");
    }

    @Test
    void agentThreshold_lowScore_retryExhausted_refuses() throws Exception {
        AgentConfig agent = new AgentConfig("测试Agent", "系统提示", 5, 0.8, 1, 5);
        Map<String, Object> data = new HashMap<>();
        data.put(QaContextKey.CHUNKS, List.of(ev()));
        data.put(QaContextKey.VERIFY_SCORE, 0.5);
        data.put(QaContextKey.RETRY_COUNT, 1);
        data.put(QaContextKey.MAX_RETRY, 2);
        data.put(QaContextKey.AGENT, agent);

        Map<String, Object> out = node.apply(new OverAllState(data));

        assertEquals(Defaults.INSUFFICIENT_EVIDENCE_REFUSAL, out.get(QaContextKey.ANSWER),
                "Agent 阈值 0.8、0.5 分且重试耗尽应拒答");
        assertEquals("TERMINAL", out.get(QaContextKey.NEXT));
    }

    @Test
    void maxRetryZero_lowScore_refusesImmediately() throws Exception {
        Map<String, Object> out = node.apply(state(List.of(ev()), 0.5, 0, 0));

        assertEquals(Defaults.INSUFFICIENT_EVIDENCE_REFUSAL, out.get(QaContextKey.ANSWER),
                "maxRetry=0 时无重试预算，低分直接拒答");
        assertEquals("TERMINAL", out.get(QaContextKey.NEXT));
    }
}
