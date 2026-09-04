package com.ai.konwledgerepo.graph;

import com.alibaba.cloud.ai.graph.OverAllState;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 有效问题优先级单元测试：
 * effectiveQuestion = RESOLVED_QUESTION > BUSINESS_QUESTION > RAW_QUESTION
 * （RESOLVED 是 BUSINESS 消歧后的规范化表达，内容覆盖相同而语义更完整，理应优先）；
 * preRewriteQuestion = BUSINESS_QUESTION > RAW_QUESTION（消歧前对照问题，永不取 RESOLVED）。
 */
class QaContextQuestionTest {

    private OverAllState state(Map<String, Object> data) {
        return new OverAllState(new HashMap<>(data));
    }

    @Test
    void effectiveQuestion_resolvedHighestPriority() {
        OverAllState s = state(Map.of(
                QaContextKey.RAW_QUESTION, "你好呀 它怎么申请？",
                QaContextKey.RESOLVED_QUESTION, "国家奖学金的申请条件是什么",
                QaContextKey.BUSINESS_QUESTION, "它怎么申请？"));
        assertEquals("国家奖学金的申请条件是什么", QaContext.effectiveQuestion(s),
                "消歧后问题应为最高优先级（BUSINESS 是其消歧前形态，降为对照）");
    }

    @Test
    void effectiveQuestion_resolvedBlankFallsToBusiness() {
        OverAllState s = state(Map.of(
                QaContextKey.RAW_QUESTION, "你好呀 它怎么申请？",
                QaContextKey.RESOLVED_QUESTION, "  ",
                QaContextKey.BUSINESS_QUESTION, "它怎么申请？"));
        assertEquals("它怎么申请？", QaContext.effectiveQuestion(s),
                "RESOLVED_QUESTION 为空白时应回落业务片段聚合（fail-open）");
    }

    @Test
    void effectiveQuestion_businessOnly() {
        OverAllState s = state(Map.of(
                QaContextKey.RAW_QUESTION, "它怎么申请？",
                QaContextKey.BUSINESS_QUESTION, "它怎么申请？"));
        assertEquals("它怎么申请？", QaContext.effectiveQuestion(s),
                "无消歧产物（改写未触发/兜底）时取业务片段聚合锚点");
    }

    @Test
    void effectiveQuestion_fallsBackToRawWhenResolvedBlank() {
        OverAllState s = state(Map.of(
                QaContextKey.RAW_QUESTION, "如何申请报销？",
                QaContextKey.RESOLVED_QUESTION, "  "));
        assertEquals("如何申请报销？", QaContext.effectiveQuestion(s),
                "RESOLVED_QUESTION 为空白时应回落原始问题（fail-open）");
    }

    @Test
    void effectiveQuestion_rawOnly() {
        OverAllState s = state(Map.of(QaContextKey.RAW_QUESTION, "如何申请报销？"));
        assertEquals("如何申请报销？", QaContext.effectiveQuestion(s));
    }

    @Test
    void preRewriteQuestion_businessElseRaw_neverResolved() {
        OverAllState multi = state(Map.of(
                QaContextKey.RAW_QUESTION, "你好呀 报销流程是什么？",
                QaContextKey.RESOLVED_QUESTION, "国家奖学金的申请条件是什么",
                QaContextKey.BUSINESS_QUESTION, "报销流程是什么？"));
        assertEquals("报销流程是什么？", QaContext.preRewriteQuestion(multi));

        OverAllState single = state(Map.of(
                QaContextKey.RAW_QUESTION, "它怎么申请？",
                QaContextKey.RESOLVED_QUESTION, "国家奖学金的申请条件是什么"));
        assertEquals("它怎么申请？", QaContext.preRewriteQuestion(single),
                "单意图对照问题应为消歧前的原始指代句（不受 RESOLVED_QUESTION 影响）");
    }
}
