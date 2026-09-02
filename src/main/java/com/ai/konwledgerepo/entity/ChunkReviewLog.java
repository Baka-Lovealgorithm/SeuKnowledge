package com.ai.konwledgerepo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * 文档精修审计日志（chunk_review_log）：记录 SUSPECT/KEEP chunk 的精修动作与编辑前后内容，
 * 保证清洗决策可追溯（谁在何时对哪个 chunk 做了什么、改了什么）。
 */
@Entity
@Table(name = "chunk_review_log")
public class ChunkReviewLog extends BaseEntity {

    /** 被审核的 chunk id */
    @Column(name = "chunk_id", nullable = false)
    private Long chunkId;

    /** 所属文档 id（冗余存储，便于按文档查询审计） */
    @Column(name = "doc_id", nullable = false)
    private Long docId;

    /** 审核动作：keep=保留 / edit=编辑并保留 / drop=删除 */
    @Column(nullable = false, length = 20)
    private String action;

    /** 动作前内容（edit 时留痕；keep/drop 可空） */
    @Column(name = "before_content", columnDefinition = "TEXT")
    private String beforeContent;

    /** 动作后内容（edit 时为编辑结果；keep 可空；drop 为丢弃前原文） */
    @Column(name = "after_content", columnDefinition = "TEXT")
    private String afterContent;

    /** 操作人（用户 id） */
    @Column(name = "user_id")
    private Long userId;

    public Long getChunkId() {
        return chunkId;
    }

    public void setChunkId(Long chunkId) {
        this.chunkId = chunkId;
    }

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

    public String getBeforeContent() {
        return beforeContent;
    }

    public void setBeforeContent(String beforeContent) {
        this.beforeContent = beforeContent;
    }

    public String getAfterContent() {
        return afterContent;
    }

    public void setAfterContent(String afterContent) {
        this.afterContent = afterContent;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }
}
