package com.ai.konwledgerepo.service.chat;

import com.ai.konwledgerepo.common.GenerationCancelledException;
import com.ai.konwledgerepo.common.SseStreamContext;
import com.ai.konwledgerepo.entity.ChatSession;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 停止生成落库裁决测试：异常携带内容优先（流式中途取消），为空时回退共享 flow 累积；
 * 两者皆空（首 token 前取消）→ 仅落用户消息，不落空 assistant 消息（防历史"空气泡"）。
 */
class ChatStreamServiceTest {

    @Test
    void resolvePartial_prefersExceptionPartial() {
        SseStreamContext.SseFlow flow = new SseStreamContext.SseFlow(new SseEmitter());
        flow.appendPartial("流式累积文本");
        assertEquals("异常携带文本", ChatStreamService.resolvePartial(new GenerationCancelledException("异常携带文本"), flow));
    }

    @Test
    void resolvePartial_fallsBackToFlowAccumulation() {
        SseStreamContext.SseFlow flow = new SseStreamContext.SseFlow(new SseEmitter());
        flow.appendPartial("流式累积文本");
        // 节点入口/同步调用路径取消：异常 partial 为空串，必须回退 flow 累积
        assertEquals("流式累积文本", ChatStreamService.resolvePartial(new GenerationCancelledException(""), flow));
    }

    @Test
    void resolvePartial_bothEmpty_returnsEmpty() {
        assertEquals("", ChatStreamService.resolvePartial(new GenerationCancelledException(""), null));
        SseStreamContext.SseFlow flow = new SseStreamContext.SseFlow(new SseEmitter());
        assertEquals("", ChatStreamService.resolvePartial(new GenerationCancelledException(""), flow));
    }

    /** 首 token 前停止（异常与 flow 累积均为空）：仅落用户消息，不落空 assistant 消息 */
    @Test
    void cancel_beforeFirstDelta_persistsQuestionOnly() {
        QaExecutionService executionService = mock(QaExecutionService.class);
        ChatSessionService sessionService = mock(ChatSessionService.class);
        ChatMessageStore messageStore = mock(ChatMessageStore.class);
        ChatSummaryService summaryService = mock(ChatSummaryService.class);
        ChatStreamService streamService = new ChatStreamService(executionService, sessionService, messageStore, summaryService);
        ChatSession session = new ChatSession();
        when(sessionService.getSession(1L, 1L, 7L)).thenReturn(session);
        when(executionService.execute(eq(1L), eq(1L), eq("问题"), eq(7L), any()))
                .thenThrow(new GenerationCancelledException(""));

        streamService.askStreamAsync(1L, 1L, "问题", new SseEmitter(), 7L);

        verify(messageStore).persistInterruptedQuestion(session, 1L, "问题", 7L);
        verify(messageStore, never()).persistInterruptedAnswer(any(), anyLong(), anyString(), anyString(), anyLong());
    }

    /** 流式中途停止（已有部分答案）：仍走 persistInterruptedAnswer 落库部分内容 */
    @Test
    void cancel_withPartial_persistsPartialAnswer() {
        QaExecutionService executionService = mock(QaExecutionService.class);
        ChatSessionService sessionService = mock(ChatSessionService.class);
        ChatMessageStore messageStore = mock(ChatMessageStore.class);
        ChatSummaryService summaryService = mock(ChatSummaryService.class);
        ChatStreamService streamService = new ChatStreamService(executionService, sessionService, messageStore, summaryService);
        ChatSession session = new ChatSession();
        when(sessionService.getSession(1L, 1L, 7L)).thenReturn(session);
        when(executionService.execute(eq(1L), eq(1L), eq("问题"), eq(7L), any()))
                .thenThrow(new GenerationCancelledException("部分答案"));

        streamService.askStreamAsync(1L, 1L, "问题", new SseEmitter(), 7L);

        verify(messageStore).persistInterruptedAnswer(session, 1L, "问题", "部分答案", 7L);
        verify(messageStore, never()).persistInterruptedQuestion(any(), anyLong(), anyString(), anyLong());
    }
}
