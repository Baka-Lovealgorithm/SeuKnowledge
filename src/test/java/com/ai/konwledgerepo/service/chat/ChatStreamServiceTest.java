package com.ai.konwledgerepo.service.chat;

import com.ai.konwledgerepo.common.GenerationCancelledException;
import com.ai.konwledgerepo.common.SseStreamContext;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 停止生成落库的部分答案裁决测试：异常携带内容优先（流式中途取消），
 * 为空时回退共享 flow 累积（节点入口/同步调用路径取消），两者皆空才落空串。
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
}
