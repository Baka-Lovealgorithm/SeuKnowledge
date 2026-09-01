package com.ai.konwledgerepo.service.chat;

import com.ai.konwledgerepo.common.BizException;
import com.ai.konwledgerepo.common.Defaults;
import com.ai.konwledgerepo.common.ErrorCodes;
import com.ai.konwledgerepo.common.RedisKeys;
import com.ai.konwledgerepo.common.SseStreamContext;
import com.ai.konwledgerepo.common.SseStreamContext.SseFlow;
import com.ai.konwledgerepo.common.TaskLock;
import com.ai.konwledgerepo.config.props.SeuQaProperties;
import com.ai.konwledgerepo.entity.ChatSession;
import com.ai.konwledgerepo.entity.KnowledgeBase;
import com.ai.konwledgerepo.graph.AgentConfig;
import com.ai.konwledgerepo.graph.QaContext;
import com.ai.konwledgerepo.graph.QaContextKey;
import com.ai.konwledgerepo.graph.QaGraphRunner;
import com.ai.konwledgerepo.service.agent.AgentService;
import com.ai.konwledgerepo.service.knowledgebase.KnowledgeBaseService;
import com.ai.konwledgerepo.service.workspace.WorkspaceAccess;
import com.alibaba.cloud.ai.graph.OverAllState;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.util.List;

/**
 * 问答执行核心：同步/流式共用，封装「锁 → 校验 → 构建输入 → 跑图 → 取结果 → 落库 → 摘要」。
 * 传输差异（SSE 推送 vs 同步返回）由调用方（{@link QaAnswerService} / {@link ChatStreamService}）处理。
 */
@Service
public class QaExecutionService {

    private final ChatSessionService sessionService;
    private final ChatHistoryService historyService;
    private final ChatSummaryService summaryService;
    private final ChatMessageStore messageStore;
    private final KnowledgeBaseService kbService;
    private final AgentService agentService;
    private final QaGraphRunner qaGraphRunner;
    private final SessionTitleService titleService;
    private final TaskLock taskLock;
    private final WorkspaceAccess workspaceAccess;
    private final Duration lockTtl;
    private final int maxRetry;
    private final int messageWindow;

    public QaExecutionService(ChatSessionService sessionService,
                              ChatHistoryService historyService,
                              ChatSummaryService summaryService,
                              ChatMessageStore messageStore,
                              KnowledgeBaseService kbService,
                              AgentService agentService,
                              QaGraphRunner qaGraphRunner,
                              SessionTitleService titleService,
                              TaskLock taskLock,
                              WorkspaceAccess workspaceAccess,
                              SeuQaProperties qaProps) {
        this.sessionService = sessionService;
        this.historyService = historyService;
        this.summaryService = summaryService;
        this.messageStore = messageStore;
        this.kbService = kbService;
        this.agentService = agentService;
        this.qaGraphRunner = qaGraphRunner;
        this.titleService = titleService;
        this.taskLock = taskLock;
        this.workspaceAccess = workspaceAccess;
        this.lockTtl = Duration.ofSeconds(Math.max(60, qaProps.qaTimeoutSeconds() + 60));
        this.maxRetry = qaProps.maxRetry();
        this.messageWindow = qaProps.messageWindow();
    }

    /**
     * 执行一次问答（同步/流式核心路径）。
     *
     * @param flow 流式 SSE 控制对象（同步路径传 null）
     * @return 答案、引用、意图
     * @throws BizException 锁失败（TOO_MANY_REQUESTS）或知识库停用等业务异常
     */
    public QaAskResult execute(Long sessionId, Long userId, String question, Long workspaceId, SseFlow flow) {
        boolean acquired = taskLock.tryAcquire(RedisKeys.askLock(sessionId), lockTtl);
        if (!acquired) {
            throw new BizException(ErrorCodes.TOO_MANY_REQUESTS, "该会话正在处理中，请稍后再试");
        }
        try {
            ChatSession session = sessionService.getSession(sessionId, userId, workspaceId);
            KnowledgeBase kb = kbService.getEntityCached(session.getKbId());
            // RESTRICTED 知识库：提问前校验当前用户可见（读语义；权限被收回后历史会话亦不可继续提问）
            workspaceAccess.requireKbAccess(kb.getId(), workspaceId, userId, false);
            if (!kb.isActive()) {
                throw new BizException("知识库已停用，无法问答");
            }

            AgentConfig agent = agentService.toAgentConfig(kb.getId(), kb.getName(), maxRetry, messageWindow);
            List<HistoryEntry> history = historyService.cachedHistory(sessionId, agent.memoryWindow());
            String memorySummary = summaryService.readSummary(sessionId)
                    .map(ChatSummaryService.SummaryRecord::text).orElse("");
            QaContext.QaInput input = new QaContext.QaInput(
                    kb.getId(), kb.getName(), sessionId, question, history, agent.maxRetry(), agent, workspaceId,
                    memorySummary);

            titleService.submitAutoTitle(session, question, workspaceId);

            SseEmitter emitter = null;
            if (flow != null) {
                SseStreamContext.setFlow(flow);
                emitter = flow.emitter;
            }
            OverAllState result = qaGraphRunner.run(input);

            String answer = result.value(QaContextKey.CHAT_ONLY_ANSWER)
                    .map(String::valueOf)
                    .or(() -> result.value(QaContextKey.ANSWER).map(String::valueOf))
                    .orElse(Defaults.QA_FALLBACK_ANSWER);
            String refs = result.value(QaContextKey.REFS).map(String::valueOf).orElse("[]");
            String intent = result.value(QaContextKey.INTENT).map(String::valueOf).orElse(null);

            if (emitter != null) {
                if (!SseStreamContext.isDeltaSent()) {
                    sendSse(emitter, "delta", answer);
                }
                // 最终权威答案：无论是否已流式预览都发送，前端以其整段上屏（与落库一致，覆盖重试/拒答/部分答案场景）
                sendSse(emitter, "answer", answer);
                sendSse(emitter, "refs", refs);
            }

            messageStore.persistAnswer(session, userId, question, answer, refs, workspaceId);
            summaryService.maybeUpdate(sessionId, workspaceId);

            return new QaAskResult(answer, refs, intent);
        } finally {
            if (flow != null) {
                SseStreamContext.clear();
            }
            taskLock.release(RedisKeys.askLock(sessionId));
        }
    }

    private void sendSse(SseEmitter emitter, String type, Object data) {
        try {
            emitter.send(SseStreamContext.event(type, data));
        } catch (Exception ignored) {
            // 客户端断开时静默忽略
        }
    }

    /** 问答执行结果（仅承载答案/引用/意图，不含传输） */
    public record QaAskResult(String answer, String refs, String intent) {
    }
}