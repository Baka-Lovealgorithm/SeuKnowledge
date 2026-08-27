package com.ai.konwledgerepo.tracing;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * List&lt;Message&gt; 重载测试：消息透传、options 挂载、stream 拼接与回调。
 */
class LlmTraceTest {

    @Test
    void callWithMessages_passesMessagesToChatModel() {
        ChatModel chat = mock(ChatModel.class);
        when(chat.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("hello")))));

        QaTracing tracing = QaTracing.disabled();
        List<Message> messages = List.of(
                new SystemMessage("You are helpful."),
                new UserMessage("What is 1+1?"));

        String result = LlmTrace.call(tracing, chat, messages);

        assertEquals("hello", result);
        verify(chat).call(any(Prompt.class));
    }

    @Test
    void callWithMessagesAndOptions_attachesOptionsToPrompt() {
        ChatModel chat = mock(ChatModel.class);
        when(chat.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("ok")))));

        QaTracing tracing = QaTracing.disabled();
        List<Message> messages = List.of(new UserMessage("test"));
        ChatOptions options = ChatOptions.builder().maxTokens(500).build();

        String result = LlmTrace.call(tracing, chat, messages, options);

        assertEquals("ok", result);
        verify(chat).call(argThat((Prompt p) -> p.getOptions() != null && p.getOptions().getMaxTokens() == 500));
    }

    @Test
    void callWithNullOptions_works() {
        ChatModel chat = mock(ChatModel.class);
        when(chat.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("no-opt")))));

        QaTracing tracing = QaTracing.disabled();
        String result = LlmTrace.call(tracing, chat, List.of(new UserMessage("q")), (ChatOptions) null);

        assertEquals("no-opt", result);
    }

    @Test
    void streamWithMessages_concatenatesAndCallsOnDelta() {
        ChatModel chat = mock(ChatModel.class);
        ChatResponse r1 = new ChatResponse(List.of(new Generation(new AssistantMessage("ab"))));
        ChatResponse r2 = new ChatResponse(List.of(new Generation(new AssistantMessage("cd"))));
        when(chat.stream(any(Prompt.class))).thenReturn(Flux.just(r1, r2));

        QaTracing tracing = QaTracing.disabled();
        List<String> deltas = new ArrayList<>();
        String result = LlmTrace.stream(tracing, chat, List.of(new UserMessage("q")), deltas::add);

        assertEquals("abcd", result);
        assertEquals(List.of("ab", "cd"), deltas);
    }

    @Test
    void stringCall_delegatesToMessages() {
        ChatModel chat = mock(ChatModel.class);
        when(chat.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("delegated")))));

        QaTracing tracing = QaTracing.disabled();
        String result = LlmTrace.call(tracing, chat, "plain text");

        assertEquals("delegated", result);
    }
}