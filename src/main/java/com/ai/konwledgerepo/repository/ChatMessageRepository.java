package com.ai.konwledgerepo.repository;

import com.ai.konwledgerepo.entity.ChatMessage;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
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
}
