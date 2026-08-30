package com.ai.konwledgerepo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * 知识库对象级授权（ACL）：RESTRICTED 知识库的可见用户名单。
 * granteeType：USER（当前支持）/ GROUP（预留）；permission：VIEW（只读）/ EDIT（可读写）。
 * 空间 ADMIN/OWNER 与知识库创建者不受本表约束（始终可访问），无需在此授权。
 */
@Entity
@Table(name = "kb_access")
public class KbAccess extends BaseEntity {

    /** 知识库 id */
    @Column(name = "kb_id", nullable = false)
    private Long kbId;

    /** 被授权主体类型：USER / GROUP */
    @Column(name = "grantee_type", nullable = false, length = 10)
    private String granteeType = "USER";

    /** 被授权主体 id（用户 id 或组 id） */
    @Column(name = "grantee_id", nullable = false)
    private Long granteeId;

    /** 权限：VIEW / EDIT */
    @Column(name = "permission", nullable = false, length = 10)
    private String permission = "VIEW";

    @Column(name = "created_by")
    private Long createdBy;

    public Long getKbId() {
        return kbId;
    }

    public void setKbId(Long kbId) {
        this.kbId = kbId;
    }

    public String getGranteeType() {
        return granteeType;
    }

    public void setGranteeType(String granteeType) {
        this.granteeType = granteeType;
    }

    public Long getGranteeId() {
        return granteeId;
    }

    public void setGranteeId(Long granteeId) {
        this.granteeId = granteeId;
    }

    public String getPermission() {
        return permission;
    }

    public void setPermission(String permission) {
        this.permission = permission;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
    }
}
