package com.ai.konwledgerepo.service.chat;

import com.ai.konwledgerepo.common.BizException;
import com.ai.konwledgerepo.common.GenerationCancelledException;
import com.ai.konwledgerepo.common.SseStreamContext;
import com.ai.konwledgerepo.common.SseStreamContext.SseFlow;
import com.ai.konwledgerepo.common.Texts;
import com.ai.konwledgerepo.entity.ChatSession;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 流式问答域（SSE）：委托 {@link QaExecutionService} 执行核心链路，保留 SSE 传输差异。
 * 由 Controller 经 {@link ChatService} 门面调用，本类 {@code @Async("streamVirtualExecutor")} 走独立虚拟线程池。
 */
@Service
public class ChatStreamService {

    private final QaExecutionService executionService;
    private final ChatSessionService sessionService;
    private final ChatMessageStore messageStore;
    private final ChatSummaryService summaryService;

    /** 活动中的流式任务（sessionId → SseFlow），供取消端点查找 */
    private final ConcurrentHashMap<Long, SseFlow> activeFlows = new ConcurrentHashMap<>();

    public ChatStreamService(QaExecutionService executionService,
                             ChatSessionService sessionService,
                             ChatMessageStore messageStore,
                             ChatSummaryService summaryService) {
        this.executionService = executionService;
        this.sessionService = sessionService;
        this.messageStore = messageStore;
        this.summaryService = summaryService;
    }

    /**
     * 流式问答（SSE）：链路执行期间答案逐 token 推送，末尾推送 done。
     */
    @Async("streamVirtualExecutor")
    public void askStreamAsync(Long sessionId, Long userId, String question, SseEmitter emitter, Long workspaceId) {
        SseFlow flow = new SseFlow(emitter);
        activeFlows.put(sessionId, flow);
        try {
            executionService.execute(sessionId, userId, question, workspaceId, flow);
            sendSseEvent(emitter, "done", null);
            emitter.complete();
        } catch (GenerationCancelledException e) {
            try {
                ChatSession session = sessionService.getSession(sessionId, userId, workspaceId);
                int messageCount = messageStore.persistInterruptedAnswer(session, userId, question, e.getPartial(), workspaceId);
                summaryService.maybeUpdate(sessionId, workspaceId, messageCount);
            } catch (Exception ex) {
                // 落库失败不影响停止语义
            }
            try {
                sendSseEvent(emitter, "stopped", null);
                emitter.complete();
            } catch (Exception ignored) {
                // 连接已断开
            }
        } catch (BizException e) {
            sendSseEvent(emitter, "error", Texts.truncate(Texts.friendlyError(e.getMessage()), 200));
            emitter.complete();
        } catch (Exception e) {
            try {
                sendSseEvent(emitter, "error", Texts.truncate(Texts.friendlyError(e.getMessage()), 200));
                emitter.completeWithError(e);
            } catch (Exception ignored) {
                // 连接已断开
            }
        } finally {
            activeFlows.remove(sessionId);
        }
    }

    /** 取消指定会话的流式生成（幂等） */
    public void cancel(Long sessionId) {
        SseFlow flow = activeFlows.get(sessionId);
        if (flow != null) {
            flow.cancelled.set(true);
        }
    }

    private void sendSseEvent(SseEmitter emitter, String type, Object data) {
        try {
            emitter.send(SseStreamContext.event(type, data));
        } catch (Exception ignored) {
            // 连接已断开
        }
    }
}