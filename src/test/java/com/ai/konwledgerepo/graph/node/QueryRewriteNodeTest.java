package com.ai.konwledgerepo.graph.node;

import com.ai.konwledgerepo.common.PromptCatalog;
import com.ai.konwledgerepo.graph.ChunkEvidence;
import com.ai.konwledgerepo.graph.QaContextKey;
import com.ai.konwledgerepo.graph.QaState;
import com.ai.konwledgerepo.model.ModelFactory;
import com.ai.konwledgerepo.tracing.QaTracing;
import com.alibaba.cloud.ai.graph.OverAllState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 问题改写节点测试：首轮解析候选查询（含空输出回退原始问题）；
 * 重试轮提示应包含缺失信息与已覆盖证据标题（定向改写）。
 */
class QueryRewriteNodeTest {

    private ModelFactory modelFactory;
    private ChatModel chat;
    private QaTracing qaTracing;
    private QueryRewriteNode node;

    @BeforeEach
    void setUp() {
        modelFactory = mock(ModelFactory.class);
        chat = mock(ChatModel.class);
        qaTracing = QaTracing.disabled();
        when(modelFactory.getChatModelByUsage(anyString(), any())).thenReturn(chat);
        node = new QueryRewriteNode(modelFactory, qaTracing, new PromptCatalog());
    }

    private void stubLlm(String text) {
        when(chat.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(text)))));
    }

    private OverAllState state(String question, int retry, String missingInfo) {
        Map<String, Object> data = new HashMap<>();
        data.put(QaContextKey.RAW_QUESTION, question);
        data.put(QaContextKey.RETRY_COUNT, retry);
        if (missingInfo != null) {
            data.put(QaContextKey.MISSING_INFO, missingInfo);
        }
        return new OverAllState(data);
    }

    private String capturedPrompt() {
        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        verify(chat).call(captor.capture());
        return captor.getValue().getContents();
    }

    @SuppressWarnings("unchecked")
    private List<String> queries(Map<String, Object> out) {
        return (List<String>) out.get(QaContextKey.QUERIES);
    }

    @Test
    void firstRound_parsesNumberedQueriesAndRoutesToRecall() throws Exception {
        stubLlm("1. 如何申请报销\n2. 报销流程是什么\n3. 报销需要什么材料");
        Map<String, Object> out = node.apply(state("如何申请报销？", 0, null));
        List<String> queries = queries(out);
        assertEquals(3, queries.size(), "最多解析 3 个候选查询");
        assertEquals("如何申请报销", queries.get(0));
        assertFalse(queries.get(0).startsWith("1."), "应剥离行首编号");
        assertEquals(QaState.KNOWLEDGE_RECALL.name(), out.get(QaContextKey.NEXT));
    }

    @Test
    void firstRound_blankOutput_fallsBackToRawQuestion() throws Exception {
        stubLlm("  \n  ");
        Map<String, Object> out = node.apply(state("如何申请报销？", 0, null));
        assertEquals(List.of("如何申请报销？"), queries(out), "空输出应回退原始问题作为兜底查询");
        assertEquals(QaState.KNOWLEDGE_RECALL.name(), out.get(QaContextKey.NEXT));
    }

    @Test
    void retryWithMissingInfo_promptContainsMissingAndCoveredTitles() throws Exception {
        stubLlm("报销申请材料清单");
        Map<String, Object> data = new HashMap<>();
        data.put(QaContextKey.RAW_QUESTION, "如何申请报销？");
        data.put(QaContextKey.RETRY_COUNT, 1);
        data.put(QaContextKey.MISSING_INFO, "缺少步骤数据");
        data.put(QaContextKey.CHUNKS, List.of(
                new ChunkEvidence(1L, 100L, 1L, "报销制度.doc", 1, "CHUNK", "报销制度", "报销制度内容", 0.9)));

        Map<String, Object> out = node.apply(new OverAllState(data));

        assertFalse(queries(out).isEmpty());
        assertEquals(QaState.KNOWLEDGE_RECALL.name(), out.get(QaContextKey.NEXT));
        String prompt = capturedPrompt();
        assertTrue(prompt.contains("缺少步骤数据"), "重试提示应包含缺失信息反馈");
        assertTrue(prompt.contains("报销制度"), "重试提示应附上一轮已覆盖证据标题");
    }

    @Test
    void retryWithoutMissingInfo_usesGenericWiderHint() throws Exception {
        stubLlm("报销 申请 流程");
        Map<String, Object> out = node.apply(state("如何申请报销？", 1, null));
        assertFalse(queries(out).isEmpty());
        assertEquals(QaState.KNOWLEDGE_RECALL.name(), out.get(QaContextKey.NEXT));
        assertTrue(capturedPrompt().contains("更宽泛"), "无缺失信息时应回退通用宽泛提示");
    }
}
