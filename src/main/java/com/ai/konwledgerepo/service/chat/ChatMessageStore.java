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
     * 问答后统一落库（无自检快照的重载，如闲聊直答）。
     * 事务在本人方法上开启后再自调用带快照的重载：自调用不过代理，但外层事务已激活，
     * 悲观锁查询仍在事务内执行——因此两个重载都必须标 {@code @Transactional}。
     */
    @Transactional
    public PersistedAnswer persistAnswer(ChatSession session, Long userId, String question, String answer, String refs,
                                         Long workspaceId) {
        return persistAnswer(session, userId, question, answer, refs, workspaceId, AnswerQuality.NONE);
    }

    /**
     * 问答后统一落库：用户/助手消息 + 会话状态（消息数/最后时间），并失效会话列表缓存。
     * 同时把本轮自检产出的质量快照（{@link AnswerQuality}）写进 ASSISTANT 行——这些值
     * 只活在图执行的 OverAllState 里，不落库就永久丢失（tracing 只写日志文件，无表），
     * 事后无法回答「被踩的答案当时自检分多少、重试了几轮」。
     * <p>
     * 标题/摘要的异步补写已剥离：标题生成在 {@link SessionTitleService}（本类仅承载补写事务），
     * 摘要生成在 {@link ChatSummaryService}（同上）。
     * <p>
     * 并发安全：事务内先对会话行加悲观锁（SELECT ... FOR UPDATE，见
     * {@link ChatSessionRepository#findByIdForUpdate}），使同一会话的落库严格串行——
     * 后到事务在锁查询处排队，读到前者已提交状态后正确累计，消息写入顺序严格交替；
     * 跨会话行互不阻塞，保持并行。锁持有时间仅为落库事务时长（毫秒级）。
     *
     * @return 落库后的消息总数（摘要增量触发基准）+ ASSISTANT 消息 id（前端点踩要拿它定位行）
     */
    @Transactional
    public PersistedAnswer persistAnswer(ChatSession session, Long userId, String question, String answer, String refs,
                                         Long workspaceId, AnswerQuality quality) {
        // 悲观锁 + 当前读：以锁查询的最新实体为准，防止并发读-改-写丢失更新
        ChatSession locked = sessionRepository.findByIdForUpdate(session.getId())
                .orElseThrow(() -> new BizException("会话不存在或已删除"));
        saveMessage(locked.getId(), MessageRole.USER.value(), question, null);
        Long answerMessageId = saveMessage(locked.getId(), MessageRole.ASSISTANT.value(), answer, refs, false, quality);
        locked.setMessageCount(locked.getMessageCount() + 2);
        locked.setLastMessageAt(java.time.LocalDateTime.now());
        sessionRepository.save(locked);
        sessionService.evictSessionList(userId, workspaceId);
        return new PersistedAnswer(locked.getMessageCount(), answerMessageId);
    }

    /** 保存单条消息：落库 + 追加会话记忆缓存 + 失效消息列表缓存。返回消息 id。 */
    public Long saveMessage(Long sessionId, String role, String content, String refs) {
        return saveMessage(sessionId, role, content, refs, false, AnswerQuality.NONE);
    }

    /** 保存单条消息（带 interrupted 标记）。返回消息 id。 */
    public Long saveMessage(Long sessionId, String role, String content, String refs, boolean interrupted) {
        return saveMessage(sessionId, role, content, refs, interrupted, AnswerQuality.NONE);
    }

    /** 保存单条消息（带 interrupted 标记 + 自检质量快照）。返回消息 id。 */
    public Long saveMessage(Long sessionId, String role, String content, String refs, boolean interrupted,
                            AnswerQuality quality) {
        ChatMessage message = new ChatMessage();
        message.setSessionId(sessionId);
        message.setRole(role);
        message.setContent(content);
        message.setRefs(refs);
        message.setInterrupted(interrupted);
        quality.applyTo(message);
        messageRepository.save(message);
        historyService.appendHistory(sessionId, role, content, interrupted);
        redisCacheService.delete(RedisKeys.messages(sessionId));
        return message.getId();
    }

    /**
     * 停止生成后持久化部分答案：USER 消息 + ASSISTANT 消息（interrupted=true）。
     * 与 {@link #persistAnswer} 一致的悲观锁事务；被停止的答案同样允许点踩，故返回消息 id。
     */
    @Transactional
    public PersistedAnswer persistInterruptedAnswer(ChatSession session, Long userId, String question, String partialAnswer,
                                                    Long workspaceId) {
        ChatSession locked = sessionRepository.findByIdForUpdate(session.getId())
                .orElseThrow(() -> new BizException("会话不存在或已删除"));
        saveMessage(locked.getId(), MessageRole.USER.value(), question, null, false);
        Long answerMessageId = saveMessage(locked.getId(), MessageRole.ASSISTANT.value(),
                partialAnswer == null ? "" : partialAnswer, "[]", true);
        locked.setMessageCount(locked.getMessageCount() + 2);
        locked.setLastMessageAt(java.time.LocalDateTime.now());
        sessionRepository.save(locked);
        sessionService.evictSessionList(userId, workspaceId);
        return new PersistedAnswer(locked.getMessageCount(), answerMessageId);
    }

    /**
     * 停止生成且未产生任何内容（首 token 前取消）：仅落 USER 消息。
     * 不落空 assistant 消息——避免历史接口返回"空气泡"、摘要输入混入空消息；
     * 问题确实问过，保留留痕（下轮问答的 LLM 上下文亦可见）。
     * 与 {@link #persistInterruptedAnswer} 一致的悲观锁事务；本路径没有答案，故消息 id 为 null。
     */
    @Transactional
    public PersistedAnswer persistInterruptedQuestion(ChatSession session, Long userId, String question, Long workspaceId) {
        ChatSession locked = sessionRepository.findByIdForUpdate(session.getId())
                .orElseThrow(() -> new BizException("会话不存在或已删除"));
        saveMessage(locked.getId(), MessageRole.USER.value(), question, null, false);
        locked.setMessageCount(locked.getMessageCount() + 1);
        locked.setLastMessageAt(java.time.LocalDateTime.now());
        sessionRepository.save(locked);
        sessionService.evictSessionList(userId, workspaceId);
        return new PersistedAnswer(locked.getMessageCount(), null);
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

    /**
     * 落库结果：会话消息总数（单调递增，作为摘要触发增量的基准）+ 本轮 ASSISTANT 消息 id。
     *
     * @param assistantMessageId 本轮答案的消息 id；仅落 USER 的路径（首 token 前取消）为 null
     */
    public record PersistedAnswer(int messageCount, Long assistantMessageId) {
    }

    /**
     * 答案自检质量快照，随答案一起落库。全字段可空：闲聊直答、未启用自检、
     * 以及采集上线前的存量行都没有值——统计侧按 null 容错，不得当作 0 分。
     *
     * @param missingInfoLimit 缺失信息入库前截断长度（列宽 500，留余量给中文）
     */
    public record AnswerQuality(Double verifyScore,
                                Double faithfulnessScore,
                                Integer retryCount,
                                String missingInfo) {

        /** 无快照（直答/取消等路径），落库时四列保持 null */
        public static final AnswerQuality NONE = new AnswerQuality(null, null, null, null);

        private static final int MISSING_INFO_LIMIT = 480;

        void applyTo(com.ai.konwledgerepo.entity.ChatMessage message) {
            message.setVerifyScore(verifyScore);
            message.setFaithfulnessScore(faithfulnessScore);
            message.setRetryCount(retryCount);
            message.setMissingInfo(missingInfo == null ? null
                    : com.ai.konwledgerepo.common.Texts.truncate(missingInfo, MISSING_INFO_LIMIT));
        }
    }
}
