package com.ai.konwledgerepo.graph.node;

import com.ai.konwledgerepo.common.PromptCatalog;
import com.ai.konwledgerepo.graph.QaContextKey;
import com.ai.konwledgerepo.graph.QaState;
import com.ai.konwledgerepo.model.ModelFactory;
import com.ai.konwledgerepo.tracing.QaTracing;
import com.alibaba.cloud.ai.graph.OverAllState;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 答案合并节点测试：纯业务透传（不调 LLM）、纯闲聊透传、混合合并（输出非空即采纳，保真靠提示词约束）、
 * 合并调用失败/空输出 → 结构化兜底。
 */
class MergeAnswerNodeTest {

    private ModelFactory modelFactory;
    private ChatModel chat;
    private QaTracing qaTracing;
    private MergeAnswerNode node;

    @BeforeEach
    void setUp() {
        modelFactory = mock(ModelFactory.class);
        chat = mock(ChatModel.class);
        qaTracing = QaTracing.disabled();
        when(modelFactory.getChatModelByUsage(anyString(), any())).thenReturn(chat);
        node = new MergeAnswerNode(modelFactory, qaTracing, new PromptCatalog());
    }

    /** 顺序 LLM 返回：首个=闲聊回复，次个=合并输出 */
    private void stubLlm(String chitchatReply, String merged) {
        when(chat.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(chitchatReply)))))
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(merged)))));
    }

    private OverAllState state(String answer, List<String> chitchatFragments, String chatOnlyAnswer) {
        Map<String, Object> data = new HashMap<>();
        if (answer != null) {
            data.put(QaContextKey.ANSWER, answer);
        }
        if (chitchatFragments != null) {
            data.put(QaContextKey.CHITCHAT_FRAGMENTS, chitchatFragments);
        }
        if (chatOnlyAnswer != null) {
            data.put(QaContextKey.CHAT_ONLY_ANSWER, chatOnlyAnswer);
        }
        return new OverAllState(data);
    }

    private static final String BUSINESS_ANSWER = "根据[1]，ZRDDS 是数据分发服务。";

    @Test
    void pureBusiness_noChitchat_passthrough_noLlmCall() throws Exception {
        Map<String, Object> out = node.apply(state(BUSINESS_ANSWER, null, null));
        assertEquals(QaState.TERMINAL.name(), out.get(QaContextKey.NEXT));
        assertNull(out.get(QaContextKey.ANSWER), "纯业务透传不应重写 ANSWER");
        verify(chat, never()).call(any(Prompt.class));
    }

    @Test
    void pureChitchat_passthroughChatOnlyAnswer_noLlmCall() throws Exception {
        Map<String, Object> out = node.apply(state(null, List.of("你好呀"), "你好！我是知识库客服助手。"));
        assertEquals(QaState.TERMINAL.name(), out.get(QaContextKey.NEXT));
        assertEquals("你好！我是知识库客服助手。", out.get(QaContextKey.ANSWER));
        assertEquals("[]", out.get(QaContextKey.REFS));
        verify(chat, never()).call(any(Prompt.class));
    }

    @Test
    void mixed_llmMerge_acceptedWhenBusinessAnswerPreserved() throws Exception {
        String merged = "我无法替你选择颜色。\n\n" + BUSINESS_ANSWER;
        stubLlm("我无法替你选择颜色。", merged);
        Map<String, Object> out = node.apply(state(BUSINESS_ANSWER, List.of("你喜欢什么颜色？"), null));
        assertEquals(QaState.TERMINAL.name(), out.get(QaContextKey.NEXT));
        assertEquals(merged, out.get(QaContextKey.ANSWER), "合并输出保留业务答案原文与引用时采用 LLM 合并");
    }

    @Test
    void mixed_mergeRewritesBusiness_stillAccepted() throws Exception {
        // 无代码级校验：合并输出（即使未逐字保留业务答案）非空即采纳，保真由 merge-answer 提示词约束
        stubLlm("我无法替你选择颜色。", "改写后的答案，没有引用[1]了");
        Map<String, Object> out = node.apply(state(BUSINESS_ANSWER, List.of("你喜欢什么颜色？"), null));
        assertEquals(QaState.TERMINAL.name(), out.get(QaContextKey.NEXT));
        assertEquals("改写后的答案，没有引用[1]了", out.get(QaContextKey.ANSWER),
                "合并输出非空即直接采纳，不因未保留业务答案原文而回退");
    }

    @Test
    void mixed_mergeThrows_fallsBackStructural() throws Exception {
        when(chat.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("我无法替你选择颜色。")))))
                .thenThrow(new RuntimeException("merge fail"));
        Map<String, Object> out = node.apply(state(BUSINESS_ANSWER, List.of("你喜欢什么颜色？"), null));
        assertEquals("我无法替你选择颜色。\n\n" + BUSINESS_ANSWER, out.get(QaContextKey.ANSWER),
                "合并调用异常时应回退结构化拼接");
    }

    @Test
    void mixed_chitchatGenerationFails_mergeStillRunsOnBlankReply() throws Exception {
        // 闲聊回复生成失败（返回空串）→ 合并仍以空串进行，不崩溃
        when(chat.call(any(Prompt.class)))
                .thenThrow(new RuntimeException("chitchat fail"))
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(BUSINESS_ANSWER)))));
        Map<String, Object> out = node.apply(state(BUSINESS_ANSWER, List.of("你喜欢什么颜色？"), null));
        assertEquals(QaState.TERMINAL.name(), out.get(QaContextKey.NEXT));
        assertEquals(BUSINESS_ANSWER, out.get(QaContextKey.ANSWER), "闲聊生成失败应回退仅保留业务答案");
    }
}
