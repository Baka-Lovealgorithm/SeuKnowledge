package com.ai.konwledgerepo.service.chat;

import com.ai.konwledgerepo.common.BizException;
import com.ai.konwledgerepo.common.Defaults;
import com.ai.konwledgerepo.common.ErrorCodes;
import com.ai.konwledgerepo.common.RedisKeys;
import com.ai.konwledgerepo.common.SseStreamContext;
import com.ai.konwledgerepo.common.TaskLock;
import com.ai.konwledgerepo.common.Texts;
import com.ai.konwledgerepo.config.props.SeuQaProperties;
import com.ai.konwledgerepo.entity.ChatSession;
import com.ai.konwledgerepo.entity.KnowledgeBase;
import com.ai.konwledgerepo.graph.AgentConfig;
import com.ai.konwledgerepo.graph.QaContext;
import com.ai.konwledgerepo.graph.QaContextKey;
import com.ai.konwledgerepo.graph.QaGraphRunner;
import com.ai.konwledgerepo.service.agent.AgentService;
import com.ai.konwledgerepo.service.chat.HistoryEntry;
import com.ai.konwledgerepo.service.knowledgebase.KnowledgeBaseService;
import com.alibaba.cloud.ai.graph.OverAllState;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

/**
 * 流式问答域（SSE）：链路执行期间答案逐 token 推送，末尾推送 refs 与 done。
 * 由 Controller 经 {@link ChatService} 门面调用，本类 {@code @Async("streamVirtualExecutor")} 走独立虚拟线程池，
 * 避免阻塞 Servlet 线程（Tomcat 已虚拟线程化）且不与文档解析/抽取竞争平台线程池；
 * 持久化统一委托 {@link ChatMessageStore}（消息与会话状态原子落库）。
 */
@Service
public class ChatStreamService {

    private final ChatSessionService sessionService;
    private final ChatHistoryService historyService;
    private final ChatMessageStore messageStore;
    private final KnowledgeBaseService kbService;
    private final AgentService agentService;
    private final QaGraphRunner qaGraphRunner;
    private final SessionTitleService titleService;
    private final TaskLock taskLock;
    private final Duration lockTtl;
    private final int maxRetry;
    private final int messageWindow;

    public ChatStreamService(ChatSessionService sessionService,
                             ChatHistoryService historyService,
                             ChatMessageStore messageStore,
                             KnowledgeBaseService kbService,
                             AgentService agentService,
                             QaGraphRunner qaGraphRunner,
                             SessionTitleService titleService,
                             TaskLock taskLock,
                             SeuQaProperties qaProps) {
        this.sessionService = sessionService;
        this.historyService = historyService;
        this.messageStore = messageStore;
        this.kbService = kbService;
        this.agentService = agentService;
        this.qaGraphRunner = qaGraphRunner;
        this.titleService = titleService;
        this.taskLock = taskLock;
        this.lockTtl = Duration.ofSeconds(Math.max(60, qaProps.qaTimeoutSeconds() + 60));
        this.maxRetry = qaProps.maxRetry();
        this.messageWindow = qaProps.messageWindow();
    }

    /**
     * 流式问答（SSE）：链路执行期间答案逐 token 推送，末尾推送 refs 与 done。
     * 由 Controller 经 {@code @Async("streamVirtualExecutor")} 提交，避免阻塞 Servlet 线程。
     * <p>
     * 会话级互斥：同一会话并发提问时，第二个请求快速失败，避免历史/落库竞态。
     */
    @Async("streamVirtualExecutor")
    public void askStreamAsync(Long sessionId, Long userId, String question, SseEmitter emitter, Long workspaceId) {
        // 会话级互斥锁（SETNX + TTL，Redis 故障时 fail-open 放行）
        boolean acquired = taskLock.tryAcquire(RedisKeys.askLock(sessionId), lockTtl);
        if (!acquired) {
            sendSseEvent(emitter, "error", "该会话正在处理中，请稍后再试");
            emitter.complete();
            return;
        }
        try {
            ChatSession session = sessionService.getSession(sessionId, userId, workspaceId);
            KnowledgeBase kb = kbService.getEntityCached(session.getKbId());
            if (!kb.isActive()) {
                throw new BizException("知识库已停用，无法问答");
            }

            AgentConfig agent = agentService.toAgentConfig(kb.getId(), kb.getName(), maxRetry, messageWindow);
            List<HistoryEntry> history = historyService.cachedHistory(sessionId, agent.memoryWindow());
            QaContext.QaInput input = new QaContext.QaInput(
                    kb.getId(), kb.getName(), sessionId, question, history, agent.maxRetry(), agent, workspaceId);

            // 标题异步生成（与链路并行，不阻塞）
            titleService.submitAutoTitle(session, question, workspaceId);

            SseStreamContext.set(emitter);
            OverAllState result = qaGraphRunner.run(input);

            String answer = result.value(QaContextKey.CHAT_ONLY_ANSWER)
                    .map(String::valueOf)
                    .or(() -> result.value(QaContextKey.ANSWER).map(String::valueOf))
                    .orElse(Defaults.QA_FALLBACK_ANSWER);
            String refs = result.value(QaContextKey.REFS).map(String::valueOf).orElse("[]");

            // 未流式输出过答案的分支（如空证据兜底）在此补发，保证前端始终有内容
            if (!SseStreamContext.isDeltaSent()) {
                sendSseEvent(emitter, "delta", answer);
            }
            sendSseEvent(emitter, "refs", refs);

            // 先持久化消息与会话状态（标题已异步提交，落库不再等待），再收尾流，保证前端结束时数据已落库
            messageStore.persistAnswer(session, userId, question, answer, refs, workspaceId);

            sendSseEvent(emitter, "done", null);
            emitter.complete();
        } catch (Exception e) {
            try {
                sendSseEvent(emitter, "error", Texts.truncate(e.getMessage(), 200));
                emitter.completeWithError(e);
            } catch (Exception ignored) {
                // 连接已断开
            }
        } finally {
            SseStreamContext.clear();
            taskLock.release(RedisKeys.askLock(sessionId));
        }
    }

    private void sendSseEvent(SseEmitter emitter, String type, Object data) {
        try {
            emitter.send(SseStreamContext.event(type, data));
        } catch (IOException e) {
            throw new BizException("SSE 发送失败");
        }
    }
}
