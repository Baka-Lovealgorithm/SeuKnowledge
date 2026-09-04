package com.ai.konwledgerepo.repository;

import com.ai.konwledgerepo.entity.ChatSession;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ChatSessionRepository extends JpaRepository<ChatSession, Long> {

    /** 会话列表：按最后对话时间倒序（最近对话的排在最前），而非创建时间 */
    List<ChatSession> findByUserIdOrderByLastMessageAtDesc(Long userId);

    /** 当前工作空间下（按知识库 id 集合）的会话列表：按最后对话时间倒序 */
    List<ChatSession> findByUserIdAndKbIdInOrderByLastMessageAtDesc(Long userId, Collection<Long> kbIds);

    List<ChatSession> findByKbIdAndUserIdOrderByLastMessageAtDesc(Long kbId, Long userId);

    /**
     * 行级悲观锁查询（SELECT ... FOR UPDATE）：同会话落库串行化入口。
     * 当前读 + 行锁：并发事务在锁查询处排队，前者提交后后者读到最新已提交状态，
     * 保证 messageCount 累加无丢失更新、消息写入顺序严格交替；跨会话行互不阻塞。
     * 锁持有时间为落库事务时长（毫秒级），单行锁无死锁风险。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from ChatSession s where s.id = :id")
    Optional<ChatSession> findByIdForUpdate(@Param("id") Long id);

    /**
     * 条件更新标题：仅当 titleAuto=true 时才写入，原子防覆盖手动 rename。
     * 返回受影响行数（0=已被手动重命名，跳过）。
     */
    @Modifying
    @Query("update ChatSession s set s.title = :title, s.titleAuto = true where s.id = :id and s.titleAuto = true")
    int updateTitleIfAuto(@Param("id") Long id, @Param("title") String title);

    /**
     * 定向更新滚动摘要（摘要文本 + 生成时的 message_count 快照）：仅触碰摘要两列，
     * 不影响标题/消息数等其它列。返回受影响行数（0=会话已删除）。
     */
    @Modifying
    @Query("update ChatSession s set s.memorySummary = :summary, s.summaryMsgCount = :count where s.id = :id")
    int updateSummary(@Param("id") Long id, @Param("summary") String summary, @Param("count") int count);
}
