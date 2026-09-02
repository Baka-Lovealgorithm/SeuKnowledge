package com.ai.konwledgerepo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * 文档级初洗/精修动作审计（save_md / accept / confirm / edit_chunk / drop_chunk / keep_chunk / unkeep）。
 * <p>
 * chunk 级编辑的内容留痕（before/after 全文）仍写 chunk_review_log；
 * 本表仅记文档级动作与前后摘要（如 md 保存前后页数/字符数），供"谁在什么时候做了什么"审计。
 */
@Entity
@Table(name = "document_curate_log")
public class DocumentCurateLog extends BaseEntity {

    @Column(name = "doc_id", nullable = false)
    private Long docId;

    @Column(nullable = false, length = 30)
    private String action;

    @Column(name = "before_summary", columnDefinition = "TEXT")
    private String beforeSummary;

    @Column(name = "after_summary", columnDefinition = "TEXT")
    private String afterSummary;

    @Column(name = "user_id")
    private Long userId;

    public Long getDocId() {
        return docId;
    }

    public void setDocId(Long docId) {
        this.docId = docId;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getBeforeSummary() {
        return beforeSummary;
    }

    public void setBeforeSummary(String beforeSummary) {
        this.beforeSummary = beforeSummary;
    }

    public String getAfterSummary() {
        return afterSummary;
    }

    public void setAfterSummary(String afterSummary) {
        this.afterSummary = afterSummary;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }
}
