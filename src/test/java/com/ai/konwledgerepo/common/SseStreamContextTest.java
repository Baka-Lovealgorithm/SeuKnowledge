package com.ai.konwledgerepo.common;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 流式上下文核心语义测试：4 参 send（flow + emitter）的 delta 累积、
 * 节点入口取消检查携带已累积部分答案（停止后按已生成内容落库的前提）。
 */
class SseStreamContextTest {

    @AfterEach
    void tearDown() {
        SseStreamContext.clear();
    }

    @Test
    void send_flowDelta_accumulatesPartial() {
        SseStreamContext.SseFlow flow = new SseStreamContext.SseFlow(new SseEmitter());
        // 未接线的 SseEmitter 推送可能抛 IllegalStateException——累积必须先于推送完成，不受影响
        SseStreamContext.send(flow, new SseEmitter(), "delta", "你好");
        SseStreamContext.send(flow, new SseEmitter(), "delta", "，世界");
        assertEquals("你好，世界", flow.partialAnswer());
    }

    @Test
    void send_nonDelta_doesNotAccumulate() {
        SseStreamContext.SseFlow flow = new SseStreamContext.SseFlow(new SseEmitter());
        SseStreamContext.send(flow, new SseEmitter(), "refs", "[1]");
        SseStreamContext.send(flow, new SseEmitter(), "done", null);
        assertEquals("", flow.partialAnswer(), "非 delta 事件不得进入部分答案累积");
    }

    @Test
    void send_nullFlowOrNullEmitter_noAccumulationNoThrow() {
        SseStreamContext.send(null, new SseEmitter(), "delta", "x");
        SseStreamContext.send(null, null, "delta", "x");
        SseStreamContext.SseFlow flow = new SseStreamContext.SseFlow(new SseEmitter());
        SseStreamContext.send(flow, null, "delta", "y");
        assertEquals("y", flow.partialAnswer(), "emitter 为 null 仍应累积（落库语义优先于推送）");
    }

    @Test
    void throwIfCancelled_carriesAccumulatedPartial() {
        SseStreamContext.SseFlow flow = new SseStreamContext.SseFlow(new SseEmitter());
        flow.appendPartial("已生成的部分答案");
        SseStreamContext.setFlow(flow);
        flow.cancelled.set(true);

        GenerationCancelledException e = assertThrows(GenerationCancelledException.class,
                SseStreamContext::throwIfCancelled);
        assertEquals("已生成的部分答案", e.getPartial(),
                "节点入口取消检查必须携带已累积内容，停止后落库不再为空");
    }

    @Test
    void partialAnswer_readsSharedFlowAcrossSnapshotRestore() {
        SseStreamContext.SseFlow flow = new SseStreamContext.SseFlow(new SseEmitter());
        SseStreamContext.setFlow(flow);
        flow.appendPartial("共享累积");
        Object snap = SseStreamContext.snapshot();
        SseStreamContext.restore(snap);
        assertEquals("共享累积", SseStreamContext.partialAnswer(),
                "快照/恢复（并行子线程路径）后仍能读到同一份累积");
        assertTrue(SseStreamContext.get() == flow.emitter);
    }
}
