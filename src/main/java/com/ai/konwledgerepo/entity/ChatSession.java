package com.ai.konwledgerepo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 问答会话。按知识库隔离会话上下文。
 */
@Entity
@Table(name = "kb_chat_session")
public class ChatSession extends BaseEntity {

    @Column(name = "kb_id", nullable = false)
    private Long kbId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(length = 255)
    private String title;

    /** 标题是否由系统自动生成（true=自动，false=用户手动重命名，手动后不再自动覆盖） */
    @Column(name = "title_auto")
    private Boolean titleAuto = Boolean.TRUE;

    /** 最后一次对话时间：创建时=创建时间，每次问答后更新；会话列表按此倒序 */
    @Column(name = "last_message_at")
    private LocalDateTime lastMessageAt;

    @Column(name = "message_count")
    private Integer messageCount = 0;

    public Long getKbId() {
        return kbId;
    }

    public void setKbId(Long kbId) {
        this.kbId = kbId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public Boolean getTitleAuto() {
        return titleAuto;
    }

    public void setTitleAuto(Boolean titleAuto) {
        this.titleAuto = titleAuto;
    }

    public LocalDateTime getLastMessageAt() {
        return lastMessageAt;
    }

    public void setLastMessageAt(LocalDateTime lastMessageAt) {
        this.lastMessageAt = lastMessageAt;
    }

    public Integer getMessageCount() {
        return messageCount;
    }

    public void setMessageCount(Integer messageCount) {
        this.messageCount = messageCount;
    }
}
