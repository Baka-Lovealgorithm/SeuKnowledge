package com.ai.konwledgerepo.graph.node;

import com.ai.konwledgerepo.common.Defaults;
import com.ai.konwledgerepo.config.props.SeuQaProperties;
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
        // 默认节点关闭部分回答（partialAnswer=false），既有低分用例仍走拒答
        node = new RetryOrFallbackNode(qaTracing, objectMapper,
                new SeuQaProperties(20, 2, 32, 30, false, false, false, 0.4, false, 60, 200));
    }

    /** 开启 partialAnswer 的节点（模拟灰度开关打开） */
    private RetryOrFallbackNode partialNode() {
        return new RetryOrFallbackNode(qaTracing, objectMapper,
                new SeuQaProperties(20, 2, 32, 30, false, false, true, 0.4, false, 60, 200));
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

    // ===== 提前终止（noImprovement） =====

    private OverAllState stateWithNoImprovement(List<ChunkEvidence> chunks, Double score, Integer retry,
                                                Integer maxRetry, boolean noImprovement) {
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
        data.put(QaContextKey.NO_IMPROVEMENT, noImprovement);
        return new OverAllState(data);
    }

    @Test
    void noImprovement_lowScore_notExhausted_refusesInsteadOfRetry() throws Exception {
        // noImprovement=true 时即使有重试预算也直接拒答，不再重试
        Map<String, Object> out = node.apply(
                stateWithNoImprovement(List.of(ev()), 0.5, 1, 2, true));

        assertEquals(Defaults.INSUFFICIENT_EVIDENCE_REFUSAL, out.get(QaContextKey.ANSWER),
                "noImprovement 时低分应直接拒答，不重试");
        assertEquals("TERMINAL", out.get(QaContextKey.NEXT));
        assertFalse(out.containsKey(QaContextKey.RETRY_COUNT), "未进入重试分支，RETRY_COUNT 不应被写回（不 +1）");
    }

    @Test
    void noImprovement_highScore_stillAnswers() throws Exception {
        // noImprovement=true 但分数达标 → 正常输出，不误伤
        Map<String, Object> out = node.apply(
                stateWithNoImprovement(List.of(ev()), 0.9, 1, 2, true));

        assertEquals("TERMINAL", out.get(QaContextKey.NEXT));
        assertFalse(out.containsKey(QaContextKey.ANSWER), "达标时不应写拒答文案");
    }

    @Test
    void noImprovement_lowScore_noRetryBudget_refuses() throws Exception {
        // noImprovement=true + 低分 + 已达上限 → 正常拒答
        Map<String, Object> out = node.apply(
                stateWithNoImprovement(List.of(ev()), 0.5, 2, 2, true));

        assertEquals(Defaults.INSUFFICIENT_EVIDENCE_REFUSAL, out.get(QaContextKey.ANSWER));
        assertEquals("TERMINAL", out.get(QaContextKey.NEXT));
    }

    // ===== 部分回答（partialAnswer） =====

    private OverAllState stateForPartial(Double score, Integer retry, Integer maxRetry,
                                         String answer, String missingInfo, List<String> contradicted) {
        Map<String, Object> data = new HashMap<>();
        data.put(QaContextKey.CHUNKS, List.of(ev()));
        if (score != null) {
            data.put(QaContextKey.VERIFY_SCORE, score);
        }
        if (retry != null) {
            data.put(QaContextKey.RETRY_COUNT, retry);
        }
        if (maxRetry != null) {
            data.put(QaContextKey.MAX_RETRY, maxRetry);
        }
        if (answer != null) {
            data.put(QaContextKey.ANSWER, answer);
        }
        if (missingInfo != null) {
            data.put(QaContextKey.MISSING_INFO, missingInfo);
        }
        if (contradicted != null) {
            data.put(QaContextKey.CONTRADICTED_CLAIMS, contradicted);
        }
        return new OverAllState(data);
    }

    @Test
    void partialEnabled_belowThreshold_noContradiction_partialAnswer() throws Exception {
        Map<String, Object> out = partialNode().apply(stateForPartial(
                0.5, 2, 2, "ZRDDS 支持多平台多协议。", "2026年产品路线图无相关信息", List.of()));

        String answer = (String) out.get(QaContextKey.ANSWER);
        assertTrue(answer.startsWith(Defaults.PARTIAL_ANSWER_PREFIX), "部分回答应带前置声明");
        assertTrue(answer.contains("ZRDDS 支持多平台多协议。"), "应保留合成答案原文");
        assertTrue(answer.contains("2026年产品路线图无相关信息"), "应附缺漏声明（缺失信息）");
        assertEquals("TERMINAL", out.get(QaContextKey.NEXT));
        JsonNode refsNode = objectMapper.readTree((String) out.get(QaContextKey.REFS));
        assertEquals(1, refsNode.size(), "部分回答仍应附候选证据供自查");
        assertFalse(out.containsKey(QaContextKey.RETRY_COUNT), "未进入重试分支，RETRY_COUNT 不应 +1");
    }

    @Test
    void partialEnabled_scoreBelowFloor_refuses() throws Exception {
        // 分数 0.3 低于 partialFloor=0.4：即使无矛盾断言也拒答，防输出几乎无支撑的答案
        Map<String, Object> out = partialNode().apply(stateForPartial(
                0.3, 2, 2, "组合答案", "缺失内容", List.of()));

        assertEquals(Defaults.INSUFFICIENT_EVIDENCE_REFUSAL, out.get(QaContextKey.ANSWER));
        assertEquals("TERMINAL", out.get(QaContextKey.NEXT));
    }

    @Test
    void partialEnabled_withContradiction_refuses() throws Exception {
        // 存在矛盾断言（与证据冲突）→ 绝不出部分回答，仍拒答
        Map<String, Object> out = partialNode().apply(stateForPartial(
                0.5, 2, 2, "组合答案", "缺失内容", List.of("与证据矛盾的断言")));

        assertEquals(Defaults.INSUFFICIENT_EVIDENCE_REFUSAL, out.get(QaContextKey.ANSWER));
        assertEquals("TERMINAL", out.get(QaContextKey.NEXT));
    }

    @Test
    void partialEnabled_noMissingInfo_suffixOmitted() throws Exception {
        Map<String, Object> out = partialNode().apply(stateForPartial(
                0.5, 2, 2, "组合答案", "", List.of()));

        String answer = (String) out.get(QaContextKey.ANSWER);
        assertTrue(answer.startsWith(Defaults.PARTIAL_ANSWER_PREFIX));
        assertTrue(answer.contains("组合答案"));
        assertFalse(answer.contains("未能得到证据支撑"), "缺失信息为空时不应附缺漏声明");
    }

    @Test
    void partialEnabled_retryBudgetStillRetries() throws Exception {
        // 还有重试预算时优先重试召回，部分回答只替代"放弃"出口，不提前启用
        Map<String, Object> out = partialNode().apply(stateForPartial(
                0.5, 0, 2, "组合答案", "缺失内容", List.of()));

        assertEquals(QaState.QUERY_REWRITE.name(), out.get(QaContextKey.NEXT));
        assertEquals(1, out.get(QaContextKey.RETRY_COUNT));
    }

    @Test
    void partialDisabled_belowThreshold_stillRefuses() throws Exception {
        // 灰度开关关闭时维持原二元行为（默认节点 partialAnswer=false）
        Map<String, Object> out = node.apply(stateForPartial(
                0.5, 2, 2, "组合答案", "缺失内容", List.of()));

        assertEquals(Defaults.INSUFFICIENT_EVIDENCE_REFUSAL, out.get(QaContextKey.ANSWER));
        assertEquals("TERMINAL", out.get(QaContextKey.NEXT));
    }
}
