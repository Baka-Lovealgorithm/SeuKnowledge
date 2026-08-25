package com.ai.konwledgerepo.graph.node;

import com.ai.konwledgerepo.common.Defaults;
import com.ai.konwledgerepo.common.PromptCatalog;
import com.ai.konwledgerepo.graph.ChunkEvidence;
import com.ai.konwledgerepo.graph.QaContext;
import com.ai.konwledgerepo.graph.QaContextKey;
import com.ai.konwledgerepo.graph.QaState;
import com.ai.konwledgerepo.model.ModelFactory;
import com.ai.konwledgerepo.tracing.QaTracing;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 答案生成节点测试：有证据 → ANSWER 非空 + REFS 为 JSON 数组 + 写回合并证据；
 * 无证据 → 兜底文案 + REFS="[]"；累计池与本轮新选中按 sourceType:chunkId 去重合并。
 */
class AnswerComposeNodeTest {

    private ModelFactory modelFactory;
    private ChatModel chat;
    private QaTracing qaTracing;
    private ObjectMapper objectMapper;
    private AnswerComposeNode node;

    @BeforeEach
    void setUp() {
        modelFactory = mock(ModelFactory.class);
        chat = mock(ChatModel.class);
        qaTracing = QaTracing.disabled();
        objectMapper = new ObjectMapper();
        when(modelFactory.getChatModelByUsage(anyString(), any())).thenReturn(chat);
        node = new AnswerComposeNode(modelFactory, objectMapper, qaTracing, new PromptCatalog());
    }

    private void stubLlm(String text) {
        when(chat.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(text)))));
    }

    private ChunkEvidence ev(long chunkId, String docName, String title) {
        return new ChunkEvidence(chunkId, 100L + chunkId, 1L, docName, 3, "CHUNK", title,
                "第一步：填写申请表", 0.85);
    }

    private OverAllState state(List<ChunkEvidence> chunks) {
        Map<String, Object> data = new HashMap<>();
        data.put(QaContextKey.RAW_QUESTION, "如何申请报销？");
        data.put(QaContextKey.CHUNKS, chunks);
        return new OverAllState(data);
    }

    @Test
    void withEvidence_generatesAnswerAndJsonRefs() throws Exception {
        stubLlm("根据[1]所述，报销需填写申请表。");
        ChunkEvidence evidence = ev(11L, "报销制度.pdf", "报销流程");

        Map<String, Object> out = node.apply(state(List.of(evidence)));

        String answer = (String) out.get(QaContextKey.ANSWER);
        assertFalse(answer == null || answer.isEmpty(), "有证据时 ANSWER 不应为空");
        assertEquals("根据[1]所述，报销需填写申请表。", answer);

        String refs = (String) out.get(QaContextKey.REFS);
        JsonNode refsNode = objectMapper.readTree(refs);
        assertTrue(refsNode.isArray(), "REFS 应为 JSON 数组字符串");
        assertEquals(1, refsNode.size());
        assertEquals(11L, refsNode.get(0).get("chunkId").asLong());
        assertEquals("报销制度.pdf", refsNode.get(0).get("docName").asText());
        assertEquals(3, refsNode.get(0).get("page").asInt());
        assertEquals("CHUNK", refsNode.get(0).get("sourceType").asText());

        assertEquals(QaState.ANSWER_VERIFY.name(), out.get(QaContextKey.NEXT));
        assertEquals(1, QaContext.chunks(out.get(QaContextKey.CHUNKS)).size(), "应写回合并后的完整证据");
    }

    @Test
    void noEvidence_returnsFallbackTextAndEmptyRefs() throws Exception {
        Map<String, Object> out = node.apply(state(List.of()));

        assertEquals(Defaults.NO_EVIDENCE_ANSWER, out.get(QaContextKey.ANSWER));
        assertEquals("[]", out.get(QaContextKey.REFS));
        assertEquals(QaState.ANSWER_VERIFY.name(), out.get(QaContextKey.NEXT));
        verify(chat, never()).call(any(Prompt.class));
    }

    @Test
    void accumulatedEvidence_mergedAndDedupedWithCurrent() throws Exception {
        stubLlm("合并后的答案");
        ChunkEvidence same = ev(11L, "报销制度.pdf", "报销流程");
        ChunkEvidence extra = new ChunkEvidence(12L, 101L, 1L, "差旅规定.pdf", 2, "CHUNK",
                "差旅标准", "高铁二等座", 0.7);

        Map<String, Object> data = new HashMap<>();
        data.put(QaContextKey.RAW_QUESTION, "如何申请报销？");
        data.put(QaContextKey.CHUNKS, List.of(same));
        data.put(QaContextKey.ACCUMULATED_CHUNKS, List.of(same, extra));

        Map<String, Object> out = node.apply(new OverAllState(data));

        List<ChunkEvidence> written = QaContext.chunks(out.get(QaContextKey.CHUNKS));
        assertEquals(2, written.size(), "累计池 ∪ 本轮新选中按 sourceType:chunkId 去重");
        assertEquals(2, objectMapper.readTree((String) out.get(QaContextKey.REFS)).size());
        assertEquals(QaState.ANSWER_VERIFY.name(), out.get(QaContextKey.NEXT));
    }

    @Test
    void outOfRangeCitation_strippedFromAnswer() throws Exception {
        // 仅 1 条证据，但 LLM 输出引用 [9] → 应被程序化移除，正文与 REFS 保持一致
        stubLlm("根据[9]所述，报销需填写申请表。");
        ChunkEvidence evidence = ev(11L, "报销制度.pdf", "报销流程");

        Map<String, Object> out = node.apply(state(List.of(evidence)));

        String answer = (String) out.get(QaContextKey.ANSWER);
        assertFalse(answer.contains("[9]"), "越界引用 [9] 应被移除: " + answer);
        assertTrue(answer.contains("报销需填写申请表"), "正文应保留: " + answer);
    }
}
