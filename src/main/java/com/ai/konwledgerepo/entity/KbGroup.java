package com.ai.konwledgerepo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * 知识库访问组（工作空间级）：供 RESTRICTED 知识库做组级别授权（双粒度 ACL）。
 * 组内成员仅限当前工作空间成员（租户隔离）；组可整体授予某知识库 VIEW/EDIT 权限，
 * 授权记录仍落在 kb_access（granteeType=GROUP，granteeId=组 id）。
 */
@Entity
@Table(name = "kb_group",
        uniqueConstraints = @UniqueConstraint(columnNames = {"workspace_id", "name"}))
public class KbGroup extends BaseEntity {

    @Column(name = "workspace_id", nullable = false)
    private Long workspaceId;

    @Column(nullable = false, length = 64)
    private String name;

    @Column(length = 200)
    private String description;

    @Column(name = "created_by")
    private Long createdBy;

    public Long getWorkspaceId() {
        return workspaceId;
    }

    public void setWorkspaceId(Long workspaceId) {
        this.workspaceId = workspaceId;
    }

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

    public Long getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
    }
}