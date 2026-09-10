package com.ai.konwledgerepo.service.chat;

import com.ai.konwledgerepo.common.BizException;
import com.ai.konwledgerepo.common.RedisCacheService;
import com.ai.konwledgerepo.common.RedisKeys;
import com.ai.konwledgerepo.dto.FeedbackRequest;
import com.ai.konwledgerepo.entity.ChatMessage;
import com.ai.konwledgerepo.entity.FeedbackReason;
import com.ai.konwledgerepo.entity.MessageFeedback;
import com.ai.konwledgerepo.entity.MessageRole;
import com.ai.konwledgerepo.repository.ChatMessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 答案评价（点赞/点踩）落库域。
 * <p>
 * 权限完全复用 {@link ChatSessionService#getSession}：它同时校验
 * 「会话属主 == 当前用户」与「会话所属空间 == 当前工作空间」，因此**只有提问本人
 * 能评价自己会话里的答案**——评价是用户反馈，不是管理动作，不给 ADMIN 开代评口子。
 * <p>
 * 与统计侧（{@code QaStatsService}）的分界：这里只写单行，跨用户读只在汇总接口发生。
 */
@Service
public class MessageFeedbackService {

    private static final Logger log = LoggerFactory.getLogger(MessageFeedbackService.class);

    /** 与 kb_chat_message.feedback_note 列宽一致（Bean Validation 已限 200，这里兜底防绕过） */
    private static final int NOTE_LIMIT = 200;

    private final ChatMessageRepository messageRepository;
    private final ChatSessionService sessionService;
    private final RedisCacheService redisCacheService;

    public MessageFeedbackService(ChatMessageRepository messageRepository,
                                  ChatSessionService sessionService,
                                  RedisCacheService redisCacheService) {
        this.messageRepository = messageRepository;
        this.sessionService = sessionService;
        this.redisCacheService = redisCacheService;
    }

    /**
     * 给一条答案打评价；{@code rating=NONE} 为撤销（四列回到 null）。
     *
     * @throws BizException 消息不存在 / 不是答案行 / 无权访问该会话
     */
    @Transactional
    public void rate(Long messageId, FeedbackRequest request, Long userId, Long workspaceId) {
        ChatMessage message = messageRepository.findById(messageId)
                .orElseThrow(() -> new BizException("消息不存在或已删除"));
        if (!MessageRole.ASSISTANT.is(message.getRole())) {
            throw new BizException("只能评价智能体的回答");
        }
        // 越权校验：非属主 / 跨工作空间都会在这里抛出（FORBIDDEN）
        sessionService.getSession(message.getSessionId(), userId, workspaceId);

        String rating = request.rating();
        int rows;
        if (MessageFeedback.NONE_REQUEST.equals(rating)) {
            rows = messageRepository.clearFeedback(messageId);
        } else {
            MessageFeedback feedback = MessageFeedback.of(rating);
            if (feedback == null) {
                throw new BizException("评价取值不合法");
            }
            // 只有点踩接受原因/说明；点赞携带原因属客户端异常，直接忽略而不是报错
            String reason = feedback == MessageFeedback.DOWN ? normalizeReason(request.reason()) : null;
            String note = feedback == MessageFeedback.DOWN ? normalizeNote(request.note()) : null;
            rows = messageRepository.updateFeedback(messageId, feedback.value(),
                    java.time.LocalDateTime.now(), reason, note);
        }
        if (rows == 0) {
            // 并发删除（会话被清）或消息根本不是答案行
            throw new BizException("消息不存在或已删除");
        }
        // 消息列表缓存存的是 ChatMessageResponse 快照：不失效就会出现「点完刷新，标记没了」
        redisCacheService.delete(RedisKeys.messages(message.getSessionId()));
        log.info("答案评价已记录 messageId={} sessionId={} rating={} reason={}",
                messageId, message.getSessionId(), rating, request.reason());
    }

    /** 原因码：空串归一为 null（用户可跳过），非法值拒绝 */
    private static String normalizeReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return null;
        }
        if (FeedbackReason.of(reason) == null) {
            throw new BizException("原因取值不合法");
        }
        return reason;
    }

    /** 补充说明：trim + 截断兜底；空白归一为 null，避免库里存一串空格 */
    private static String normalizeNote(String note) {
        if (note == null) {
            return null;
        }
        String trimmed = note.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() > NOTE_LIMIT ? trimmed.substring(0, NOTE_LIMIT) : trimmed;
    }
}
