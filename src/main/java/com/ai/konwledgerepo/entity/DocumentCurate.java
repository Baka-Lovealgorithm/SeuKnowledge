package com.ai.konwledgerepo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * 文档人工清洗（md 策展）内容：分页分段 + append-only 版本行。
 * <p>
 * 每次整篇保存插入新版本的全部页行（version 递增），最新 = max(version)；
 * 版本行即审计（相邻版本按页对比即 diff），历史版本保留最近 N 版（清理随保存动作执行）。
 * 行粒度 = 一页（单页 KB 级，MEDIUMTEXT 上限 16MB 远高于实际），避免大字段就地更新。
 */
@Entity
@Table(name = "kb_document_curate")
public class DocumentCurate extends BaseEntity {

    @Column(name = "doc_id", nullable = false)
    private Long docId;

    /** 版本号（从 1 递增，append-only） */
    @Column(nullable = false)
    private Integer version;

    /** 页码（1 起始物理页；LlamaParse 整篇回退时为 0） */
    @Column(name = "page_num", nullable = false)
    private Integer pageNum;

    /** 该页 markdown 全文 */
    @Column(nullable = false, columnDefinition = "MEDIUMTEXT")
    private String content;

    @Column(name = "updated_by", nullable = false)
    private Long updatedBy;

    public Long getDocId() {
        return docId;
    }

    public void setDocId(Long docId) {
        this.docId = docId;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public Integer getPageNum() {
        return pageNum;
    }

    public void setPageNum(Integer pageNum) {
        this.pageNum = pageNum;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public Long getUpdatedBy() {
        return updatedBy;
    }

    public void setUpdatedBy(Long updatedBy) {
        this.updatedBy = updatedBy;
    }
}
