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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 意图路由节点测试：LLM 输出含 BUSINESS → 业务链路（QUERY_REWRITE）；
 * 否则兜底闲聊链路（CHAT_ONLY）。覆盖大小写与空输出边界。
 */
class IntentRouteNodeTest {

    private ModelFactory modelFactory;
    private ChatModel chat;
    private QaTracing qaTracing;
    private IntentRouteNode node;

    @BeforeEach
    void setUp() {
        modelFactory = mock(ModelFactory.class);
        chat = mock(ChatModel.class);
        qaTracing = QaTracing.disabled();
        when(modelFactory.getChatModelByUsage(anyString(), any())).thenReturn(chat);
        node = new IntentRouteNode(modelFactory, qaTracing, new PromptCatalog());
    }

    private void stubLlm(String text) {
        when(chat.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(text)))));
    }

    private OverAllState state(String question) {
        return new OverAllState(new HashMap<>(Map.of(QaContextKey.RAW_QUESTION, question)));
    }

    @Test
    void businessIntent_routesToQueryRewrite() throws Exception {
        stubLlm("该问题属于 BUSINESS 业务咨询");
        Map<String, Object> out = node.apply(state("如何申请报销？"));
        assertEquals("BUSINESS", out.get(QaContextKey.INTENT));
        assertEquals(QaState.QUERY_REWRITE.name(), out.get(QaContextKey.NEXT));
    }

    @Test
    void chitchatIntent_routesToChatOnly() throws Exception {
        stubLlm("CHITCHAT");
        Map<String, Object> out = node.apply(state("你好呀"));
        assertEquals("CHITCHAT", out.get(QaContextKey.INTENT));
        assertEquals(QaState.CHAT_ONLY.name(), out.get(QaContextKey.NEXT));
    }

    @Test
    void lowercaseBusiness_stillRecognizedAsBusiness() throws Exception {
        stubLlm("这个属于 business 咨询");
        Map<String, Object> out = node.apply(state("问题"));
        assertEquals("BUSINESS", out.get(QaContextKey.INTENT));
        assertEquals(QaState.QUERY_REWRITE.name(), out.get(QaContextKey.NEXT));
    }

    @Test
    void blankLlmOutput_fallsBackToChitchat() throws Exception {
        stubLlm("");
        Map<String, Object> out = node.apply(state("问题"));
        assertEquals("CHITCHAT", out.get(QaContextKey.INTENT));
        assertEquals(QaState.CHAT_ONLY.name(), out.get(QaContextKey.NEXT));
    }
}
