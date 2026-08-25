package com.ai.konwledgerepo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * 会话消息。refs 存证据引用 JSON。
 */
@Entity
@Table(name = "kb_chat_message")
public class ChatMessage extends BaseEntity {

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    /** USER / ASSISTANT */
    @Column(nullable = false, length = 20)
    private String role;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    /** 证据引用 JSON：[{"docName":"xx","page":1,"chunkId":1}] */
    @Column(columnDefinition = "TEXT")
    private String refs;

    /** 意图分析结果（BUSINESS / CHITCHAT 等），可为空 */
    @Column(length = 32)
    private String intent;

    public Long getSessionId() {
        return sessionId;
    }

    public void setSessionId(Long sessionId) {
        this.sessionId = sessionId;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getRefs() {
        return refs;
    }

    public void setRefs(String refs) {
        this.refs = refs;
    }

    public String getIntent() {
        return intent;
    }

    public void setIntent(String intent) {
        this.intent = intent;
    }
}
