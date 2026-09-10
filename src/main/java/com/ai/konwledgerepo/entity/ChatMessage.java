package com.ai.konwledgerepo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 会话消息。refs 存证据引用 JSON。
 * <p>
 * 反馈与答案质量快照两组列**仅 ASSISTANT 行有值**（USER 行恒为 null），
 * 全部可空：存量数据（本次采集上线前落库的消息）与闲聊/直答路径均为 null，
 * 统计侧必须按 null 容错，不能把 null 当 0 分参与均值。
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

    /** 是否被用户中途停止 */
    @Column
    private Boolean interrupted;

    /** 用户反馈：UP / DOWN（与 {@link MessageRole} 同款按字符串存储）；null = 未评价 */
    @Column(length = 10)
    private String feedback;

    /** 反馈时间（撤销评价时一并清空） */
    @Column(name = "feedback_at")
    private LocalDateTime feedbackAt;

    /** 点踩原因码（FeedbackReason 的 name）；null 表示用户跳过了原因 */
    @Column(name = "feedback_reason", length = 32)
    private String feedbackReason;

    /** 点踩补充说明（写入前截断至 200 字） */
    @Column(name = "feedback_note", length = 200)
    private String feedbackNote;

    /** 答案自检完整性分快照（AnswerVerify 阶段一）；未自检/历史数据为 null */
    @Column(name = "verify_score")
    private Double verifyScore;

    /** 事实一致性分快照（SUPPORTED 断言占比 0~1）；未跑阶段二为 null */
    @Column(name = "faithfulness_score")
    private Double faithfulnessScore;

    /** 重试轮数快照 */
    @Column(name = "retry_count")
    private Integer retryCount;

    /** 自检「缺失信息」快照（写入前截断至 500 字），用于定位"被踩的答案缺什么" */
    @Column(name = "missing_info", length = 500)
    private String missingInfo;

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

    public Boolean getInterrupted() {
        return interrupted;
    }

    public void setInterrupted(Boolean interrupted) {
        this.interrupted = interrupted;
    }

    public String getFeedback() {
        return feedback;
    }

    public void setFeedback(String feedback) {
        this.feedback = feedback;
    }

    public LocalDateTime getFeedbackAt() {
        return feedbackAt;
    }

    public void setFeedbackAt(LocalDateTime feedbackAt) {
        this.feedbackAt = feedbackAt;
    }

    public String getFeedbackReason() {
        return feedbackReason;
    }

    public void setFeedbackReason(String feedbackReason) {
        this.feedbackReason = feedbackReason;
    }

    public String getFeedbackNote() {
        return feedbackNote;
    }

    public void setFeedbackNote(String feedbackNote) {
        this.feedbackNote = feedbackNote;
    }

    public Double getVerifyScore() {
        return verifyScore;
    }

    public void setVerifyScore(Double verifyScore) {
        this.verifyScore = verifyScore;
    }

    public Double getFaithfulnessScore() {
        return faithfulnessScore;
    }

    public void setFaithfulnessScore(Double faithfulnessScore) {
        this.faithfulnessScore = faithfulnessScore;
    }

    public Integer getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(Integer retryCount) {
        this.retryCount = retryCount;
    }

    public String getMissingInfo() {
        return missingInfo;
    }

    public void setMissingInfo(String missingInfo) {
        this.missingInfo = missingInfo;
    }
}
