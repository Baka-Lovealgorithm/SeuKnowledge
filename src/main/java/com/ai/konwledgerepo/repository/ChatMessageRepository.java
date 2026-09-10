package com.ai.konwledgerepo.repository;

import com.ai.konwledgerepo.entity.ChatMessage;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    List<ChatMessage> findBySessionIdOrderByIdAsc(Long sessionId);

    /** 最近 N 条消息（对话记忆窗口，N 由调用方通过 Pageable 指定，支持 Agent 记忆策略动态配置） */
    List<ChatMessage> findBySessionIdOrderByIdDesc(Long sessionId, Pageable pageable);

    long countBySessionId(Long sessionId);

    /** 删除会话时级联删除消息 */
    void deleteBySessionId(Long sessionId);

    /** 各会话最后一条消息的创建时间（旧会话补偿 lastMessageAt 用，按 sessionId 分组） */
    @Query("select m.sessionId, max(m.createdAt) from ChatMessage m where m.sessionId in :ids group by m.sessionId")
    List<Object[]> findMaxCreatedAtBySessionIds(@Param("ids") Collection<Long> sessionIds);

    /** 一批会话中按 id 升序的消息（旧会话自动命名时取每会话第一条 USER 消息） */
    List<ChatMessage> findBySessionIdInAndRoleOrderByIdAsc(Collection<Long> sessionIds, String role);

    /**
     * 写入答案评价（定向 update，只碰反馈四列）：不动 messageCount、不加悲观锁，
     * 因此不会与问答落库事务抢会话行锁。{@code role='ASSISTANT'} 写进 where，
     * 使"给用户消息打评价"在 SQL 层就不可能命中（返回 0 行）。
     * 返回受影响行数（0=消息不存在、已随会话删除、或不是答案行）。
     */
    @Modifying
    @Query("update ChatMessage m set m.feedback = :feedback, m.feedbackAt = :at, "
            + "m.feedbackReason = :reason, m.feedbackNote = :note "
            + "where m.id = :id and m.role = 'ASSISTANT'")
    int updateFeedback(@Param("id") Long id, @Param("feedback") String feedback,
                       @Param("at") java.time.LocalDateTime at, @Param("reason") String reason,
                       @Param("note") String note);

    /**
     * 撤销评价：四列一并回到未评价状态。
     * 用字面量 null 而非绑定参数——多列同时绑 null 需要 Hibernate 猜类型，
     * 语义上又必须清空，直接写死更稳（回归覆盖见 MessageFeedbackServiceTest）。
     */
    @Modifying
    @Query("update ChatMessage m set m.feedback = null, m.feedbackAt = null, "
            + "m.feedbackReason = null, m.feedbackNote = null "
            + "where m.id = :id and m.role = 'ASSISTANT'")
    int clearFeedback(@Param("id") Long id);

    /** 一批答案各「之前最近的 USER 消息」（点踩明细取原问题用，一条查询代替逐行 N+1） */
    @Query("select a.id, q.content from ChatMessage a, ChatMessage q "
            + "where a.id in :answerIds and q.sessionId = a.sessionId and q.role = 'USER' and q.id = "
            + "(select max(q2.id) from ChatMessage q2 where q2.sessionId = a.sessionId "
            + "and q2.role = 'USER' and q2.id < a.id)")
    List<Object[]> findQuestionByAnswerIds(@Param("answerIds") Collection<Long> answerIds);

    // ===== 反馈汇总只读查询（跨用户读，仅在 @AdminOrAbove 的统计接口下暴露） =====

    /**
     * 一次聚合出总览的全部标量指标（按空间 kbId 集合 + 时间窗过滤 ASSISTANT 回答）。
     * <p>
     * 列序即消费顺序，改动必须同步 {@code QaStatsService} 的取值下标：
     * 0 回答数 / 1 点赞数 / 2 点踩数 / 3 中断数 / 4 无证据数 /
     * 5 含自检快照数 / 6 自检分均值 / 7 事实一致性均值 / 8 被踩答案的自检分均值。
     * <p>
     * 均值与 {@code count(m.verifyScore)} 只统计非空行——存量数据与闲聊直答没有快照，
     * 把它们的 null 当 0 参与计算会把均值压成假低。
     * 注意：调用方必须保证 {@code kbIds} 非空，JPQL 的 {@code in :kbIds} 遇到空集合会生成非法 SQL。
     */
    @Query("select count(m), "
            + "sum(case when m.feedback = 'UP' then 1 else 0 end), "
            + "sum(case when m.feedback = 'DOWN' then 1 else 0 end), "
            + "sum(case when m.interrupted = true then 1 else 0 end), "
            + "sum(case when m.refs is null or m.refs = '[]' or m.refs = '' then 1 else 0 end), "
            + "count(m.verifyScore), avg(m.verifyScore), avg(m.faithfulnessScore), "
            + "avg(case when m.feedback = 'DOWN' then m.verifyScore end) "
            + "from ChatMessage m, ChatSession s "
            + "where m.sessionId = s.id and s.kbId in :kbIds and m.role = 'ASSISTANT' and m.createdAt >= :from")
    List<Object[]> aggregateFeedbackOverview(@Param("kbIds") Collection<Long> kbIds,
                                             @Param("from") java.time.LocalDateTime from);

    /** 点踩原因分布（含未填原因的 null 桶），按数量倒序 */
    @Query("select m.feedbackReason, count(m) from ChatMessage m, ChatSession s "
            + "where m.sessionId = s.id and s.kbId in :kbIds and m.feedback = 'DOWN' and m.createdAt >= :from "
            + "group by m.feedbackReason order by count(m) desc")
    List<Object[]> aggregateDislikeReasons(@Param("kbIds") Collection<Long> kbIds,
                                           @Param("from") java.time.LocalDateTime from);

    /** 点踩明细分页（按评价时间倒序）：返回 {@code [ChatMessage, sessionTitle]} */
    @Query("select m, s.title from ChatMessage m, ChatSession s "
            + "where m.sessionId = s.id and s.kbId in :kbIds and m.feedback = 'DOWN' and m.createdAt >= :from "
            + "order by m.feedbackAt desc, m.id desc")
    List<Object[]> findDislikedAnswers(@Param("kbIds") Collection<Long> kbIds,
                                       @Param("from") java.time.LocalDateTime from,
                                       org.springframework.data.domain.Pageable pageable);

    /** 点踩明细总数（分页用，条件与 {@link #findDislikedAnswers} 严格一致） */
    @Query("select count(m) from ChatMessage m, ChatSession s "
            + "where m.sessionId = s.id and s.kbId in :kbIds and m.feedback = 'DOWN' and m.createdAt >= :from")
    long countDislikedAnswers(@Param("kbIds") Collection<Long> kbIds,
                              @Param("from") java.time.LocalDateTime from);
}
