package com.ai.konwledgerepo.service.chat;

import com.ai.konwledgerepo.common.BizException;
import com.ai.konwledgerepo.common.RedisCacheService;
import com.ai.konwledgerepo.common.RedisKeys;
import com.ai.konwledgerepo.entity.ChatMessage;
import com.ai.konwledgerepo.entity.ChatSession;
import com.ai.konwledgerepo.entity.MessageRole;
import com.ai.konwledgerepo.repository.ChatMessageRepository;
import com.ai.konwledgerepo.repository.ChatSessionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 消息落库域：问答后统一持久化（消息 + 会话状态 + 标题），事务边界唯一承载点。
 * 同步 ask 与流式 ask 共用，保证「消息与会话持久化原子完成、历史一致」。
 */
@Service
public class ChatMessageStore {

    private final ChatMessageRepository messageRepository;
    private final ChatSessionRepository sessionRepository;
    private final RedisCacheService redisCacheService;
    private final ChatHistoryService historyService;
    private final ChatSessionService sessionService;

    public ChatMessageStore(ChatMessageRepository messageRepository,
                            ChatSessionRepository sessionRepository,
                            RedisCacheService redisCacheService,
                            ChatHistoryService historyService,
                            ChatSessionService sessionService) {
        this.messageRepository = messageRepository;
        this.sessionRepository = sessionRepository;
        this.redisCacheService = redisCacheService;
        this.historyService = historyService;
        this.sessionService = sessionService;
    }

    /**
     * 问答后统一落库：用户/助手消息 + 会话状态（消息数/最后时间），并失效会话列表缓存。
     * 返回落库后的会话消息总数（单调递增，作为摘要触发增量的基准）。
     * <p>
     * 标题/摘要的异步补写已剥离：标题生成在 {@link SessionTitleService}（本类仅承载补写事务），
     * 摘要生成在 {@link ChatSummaryService}（同上）。
     * <p>
     * 并发安全：事务内先对会话行加悲观锁（SELECT ... FOR UPDATE，见
     * {@link ChatSessionRepository#findByIdForUpdate}），使同一会话的落库严格串行——
     * 后到事务在锁查询处排队，读到前者已提交状态后正确累计，消息写入顺序严格交替；
     * 跨会话行互不阻塞，保持并行。锁持有时间仅为落库事务时长（毫秒级）。
     */
    @Transactional
    public int persistAnswer(ChatSession session, Long userId, String question, String answer, String refs,
                             Long workspaceId) {
        // 悲观锁 + 当前读：以锁查询的最新实体为准，防止并发读-改-写丢失更新
        ChatSession locked = sessionRepository.findByIdForUpdate(session.getId())
                .orElseThrow(() -> new BizException("会话不存在或已删除"));
        saveMessage(locked.getId(), MessageRole.USER.value(), question, null);
        saveMessage(locked.getId(), MessageRole.ASSISTANT.value(), answer, refs);
        locked.setMessageCount(locked.getMessageCount() + 2);
        locked.setLastMessageAt(java.time.LocalDateTime.now());
        sessionRepository.save(locked);
        sessionService.evictSessionList(userId, workspaceId);
        return locked.getMessageCount();
    }

    /** 保存单条消息：落库 + 追加会话记忆缓存 + 失效消息列表缓存 */
    public void saveMessage(Long sessionId, String role, String content, String refs) {
        ChatMessage message = new ChatMessage();
        message.setSessionId(sessionId);
        message.setRole(role);
        message.setContent(content);
        message.setRefs(refs);
        messageRepository.save(message);
        historyService.appendHistory(sessionId, role, content);
        redisCacheService.delete(RedisKeys.messages(sessionId));
    }

    /** 保存单条消息（带 interrupted 标记） */
    public void saveMessage(Long sessionId, String role, String content, String refs, boolean interrupted) {
        ChatMessage message = new ChatMessage();
        message.setSessionId(sessionId);
        message.setRole(role);
        message.setContent(content);
        message.setRefs(refs);
        message.setInterrupted(interrupted);
        messageRepository.save(message);
        historyService.appendHistory(sessionId, role, content, interrupted);
        redisCacheService.delete(RedisKeys.messages(sessionId));
    }

    /**
     * 停止生成后持久化部分答案：USER 消息 + ASSISTANT 消息（interrupted=true）。
     * 与 {@link #persistAnswer} 一致的悲观锁事务，返回落库后的会话消息总数。
     */
    @Transactional
    public int persistInterruptedAnswer(ChatSession session, Long userId, String question, String partialAnswer,
                                        Long workspaceId) {
        ChatSession locked = sessionRepository.findByIdForUpdate(session.getId())
                .orElseThrow(() -> new BizException("会话不存在或已删除"));
        saveMessage(locked.getId(), MessageRole.USER.value(), question, null, false);
        saveMessage(locked.getId(), MessageRole.ASSISTANT.value(), partialAnswer == null ? "" : partialAnswer, "[]", true);
        locked.setMessageCount(locked.getMessageCount() + 2);
        locked.setLastMessageAt(java.time.LocalDateTime.now());
        sessionRepository.save(locked);
        sessionService.evictSessionList(userId, workspaceId);
        return locked.getMessageCount();
    }

    /**
     * 标题补写（独立短事务）：条件更新 title/titleAuto，原子防覆盖手动 rename。
     * 供 {@link SessionTitleService} 异步线程跨 bean 调用——事务经代理生效，
     * 避免 @Transactional 自调用失效（异步线程无事务导致 update 报错）。
     * 返回受影响行数（0=已被手动重命名，跳过）。
     */
    @Transactional
    public int applyTitleIfAuto(Long sessionId, String title) {
        return sessionRepository.updateTitleIfAuto(sessionId, title);
    }

    /**
     * 滚动摘要落库（独立短事务）：定向更新摘要文本 + message_count 快照，仅触碰摘要两列。
     * 供 {@link ChatSummaryService} 异步线程跨 bean 调用（事务经代理生效）。
     * 返回受影响行数（0=会话已删除，调用方应放弃写缓存）。
     */
    @Transactional
    public int persistSummary(Long sessionId, String summary, int summaryMsgCount) {
        return sessionRepository.updateSummary(sessionId, summary, summaryMsgCount);
    }
}
