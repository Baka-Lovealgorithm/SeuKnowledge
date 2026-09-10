package com.ai.konwledgerepo.common;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

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

    /**
     * 核心回归：send 在 ThreadLocal 不可见的线程上推送失败时，必须对<b>显式传入的 flow</b> 置取消位。
     * <p>
     * 历史缺陷：{@code send} 的 catch 调 {@code requestCancel()}，而后者读当前线程的 FLOW。
     * 流式回调（Reactor doOnNext）运行在供应商/网络线程，FLOW 为 null，导致客户端断开后
     * 取消信号被静默丢弃、生成继续空转到超时。本用例把主线程上下文清空以模拟该场景。
     */
    @Test
    void send_pushFailure_cancelsExplicitFlowEvenWithoutThreadLocal() throws IOException {
        SseStreamContext.SseFlow flow = new SseStreamContext.SseFlow(new SseEmitter());
        SseEmitter broken = mock(SseEmitter.class);
        doThrow(new IOException("Broken pipe")).when(broken).send(any(SseEmitter.SseEventBuilder.class));
        // 模拟回调线程：本线程没有任何 ThreadLocal 上下文
        SseStreamContext.clear();

        SseStreamContext.send(flow, broken, "delta", "被中断的片段");

        assertTrue(flow.cancelled.get(),
                "推送失败必须取消显式传入的 flow，不能因 ThreadLocal 为空而丢弃取消信号");
        assertEquals("被中断的片段", flow.partialAnswer(), "推失败前仍应先累积部分答案");
    }

    /** sendStage 推送失败同样必须取消当前线程的 flow（节点线程路径） */
    @Test
    void sendStage_pushFailure_cancelsCurrentFlow() throws IOException {
        // sendStage 内部按 ThreadLocal 取 flow/emitter，故直接把 flow 的 emitter 设为必失败的 mock
        SseEmitter broken = mock(SseEmitter.class);
        doThrow(new IOException("Broken pipe")).when(broken).send(any(SseEmitter.SseEventBuilder.class));
        SseStreamContext.SseFlow brokenFlow = new SseStreamContext.SseFlow(broken);
        SseStreamContext.setFlow(brokenFlow);

        SseStreamContext.sendStage("ANSWER_COMPOSE", "答案生成");

        assertTrue(brokenFlow.cancelled.get(), "阶段事件推送失败应取消当前 flow");
        assertTrue(SseStreamContext.isCancelled(), "取消位应能被同线程后续节点读到");
    }

    /** cancel(flow) 对 null 安全：无流式上下文的调用方不应抛异常 */
    @Test
    void cancel_nullFlow_noThrow() {
        SseStreamContext.cancel(null);
    }
}
