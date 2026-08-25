package com.ai.konwledgerepo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * 工作空间成员（角色绑定）。角色：OWNER / ADMIN / EDITOR / MEMBER。
 * 一期单工作空间：一个用户一条记录（unique(workspace_id, user_id) 预留多工作空间）。
 */
@Entity
@Table(name = "kb_workspace_member",
        uniqueConstraints = @UniqueConstraint(columnNames = {"workspace_id", "user_id"}))
public class WorkspaceMember extends BaseEntity {

    /** 角色：OWNER 拥有者 / ADMIN 管理员 / EDITOR 编辑者 / MEMBER 普通成员 */
    public static final String ROLE_OWNER = "OWNER";
    public static final String ROLE_ADMIN = "ADMIN";
    public static final String ROLE_EDITOR = "EDITOR";
    public static final String ROLE_MEMBER = "MEMBER";

    @Column(name = "workspace_id", nullable = false)
    private Long workspaceId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 20)
    private String role;

    public Long getWorkspaceId() {
        return workspaceId;
    }

    public void setWorkspaceId(Long workspaceId) {
        this.workspaceId = workspaceId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }
}
