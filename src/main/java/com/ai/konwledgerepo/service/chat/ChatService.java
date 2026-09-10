package com.ai.konwledgerepo.service.chat;

import com.ai.konwledgerepo.dto.AskResponse;
import com.ai.konwledgerepo.dto.FeedbackRequest;
import com.ai.konwledgerepo.entity.ChatMessage;
import com.ai.konwledgerepo.entity.ChatSession;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * 会话与问答门面：按职责转发到会话域 / 问答域 / 流式域，保持对外 API 稳定。
 * <ul>
 *   <li>会话 CRUD 与消息读取 → {@link ChatSessionService}</li>
 *   <li>会话记忆窗口 → {@link ChatHistoryService}</li>
 *   <li>问答结果落库（事务边界） → {@link ChatMessageStore}</li>
 *   <li>同步问答（状态图，实时上下文） → {@link QaAnswerService}</li>
 *   <li>流式问答（SSE + @Async） → {@link ChatStreamService}</li>
 *   <li>答案评价（点赞/点踩） → {@link MessageFeedbackService}</li>
 * </ul>
 */
@Service
public class ChatService {

    private final ChatSessionService sessionService;
    private final QaAnswerService answerService;
    private final ChatStreamService streamService;
    private final MessageFeedbackService feedbackService;

    public ChatService(ChatSessionService sessionService,
                       QaAnswerService answerService,
                       ChatStreamService streamService,
                       MessageFeedbackService feedbackService) {
        this.sessionService = sessionService;
        this.answerService = answerService;
        this.streamService = streamService;
        this.feedbackService = feedbackService;
    }

    /** 创建会话：校验知识库属于当前工作空间 */
    public ChatSession createSession(Long kbId, Long userId, Long workspaceId) {
        return sessionService.createSession(kbId, userId, workspaceId);
    }

    /** 会话列表（限当前工作空间） */
    public List<ChatSession> listSessions(Long userId, Long workspaceId) {
        return sessionService.listSessions(userId, workspaceId);
    }

    /** 重命名会话（手动命名后不再被自动标题覆盖；限当前工作空间） */
    public ChatSession rename(Long sessionId, Long userId, String title, Long workspaceId) {
        return sessionService.rename(sessionId, userId, title, workspaceId);
    }

    /** 删除会话（级联删除消息；限当前工作空间） */
    public void deleteSession(Long sessionId, Long userId, Long workspaceId) {
        sessionService.deleteSession(sessionId, userId, workspaceId);
    }

    /** 会话消息列表（限当前工作空间） */
    public List<ChatMessage> messages(Long sessionId, Long userId, Long workspaceId) {
        return sessionService.messages(sessionId, userId, workspaceId);
    }

    /** 同步问答（非流式） */
    public AskResponse ask(Long sessionId, Long userId, String question, Long workspaceId) {
        return answerService.ask(sessionId, userId, question, workspaceId);
    }

    /** 流式问答（SSE）：由 ChatStreamService 异步执行 */
    public void askStreamAsync(Long sessionId, Long userId, String question, SseEmitter emitter, Long workspaceId) {
        streamService.askStreamAsync(sessionId, userId, question, emitter, workspaceId);
    }

    /** 取消流式问答（幂等，仅校验归属） */
    public void cancelAsk(Long sessionId, Long userId, Long workspaceId) {
        sessionService.getSession(sessionId, userId, workspaceId); // 校验归属
        streamService.cancel(sessionId);
    }

    /** 评价答案（点赞/点踩/撤销）：仅提问本人可评，校验在 MessageFeedbackService 内复用会话归属 */
    public void rateMessage(Long messageId, FeedbackRequest request, Long userId, Long workspaceId) {
        feedbackService.rate(messageId, request, userId, workspaceId);
    }
}
