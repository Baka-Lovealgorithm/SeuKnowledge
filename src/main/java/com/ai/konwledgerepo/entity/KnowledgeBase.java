package com.ai.konwledgerepo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * 知识库。
 */
@Entity
@Table(name = "kb_knowledge_base")
public class KnowledgeBase extends BaseEntity {

    @Column(nullable = false, length = 128)
    private String name;

    @Column(length = 500)
    private String description;

    /** 状态：DRAFT / BUILDING / AVAILABLE / ERROR / DISABLED */
    @Column(nullable = false, length = 20)
    private String status = KbStatus.DRAFT.value();

    @Column(nullable = false)
    private Boolean archived = false;

    @Column(name = "created_by")
    private Long createdBy;

    /** 归属工作空间（一期单工作空间，存量数据初始化时回填） */
    @Column(name = "workspace_id")
    private Long workspaceId;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Boolean getArchived() {
        return archived;
    }

    public void setArchived(Boolean archived) {
        this.archived = archived;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
    }

    public Long getWorkspaceId() {
        return workspaceId;
    }

    public void setWorkspaceId(Long workspaceId) {
        this.workspaceId = workspaceId;
    }

    /** 知识库是否可用：未归档且未停用（DRAFT/BUILDING/AVAILABLE/ERROR 均可问答） */
    public boolean isActive() {
        return !Boolean.TRUE.equals(archived) && !KbStatus.DISABLED.is(status);
    }
}
