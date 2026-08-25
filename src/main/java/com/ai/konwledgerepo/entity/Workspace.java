package com.ai.konwledgerepo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * 工作空间。一期为单工作空间（一个团队协作），Owner 唯一；
 * 数据模型预留多工作空间升级能力。
 */
@Entity
@Table(name = "kb_workspace")
public class Workspace extends BaseEntity {

    @Column(nullable = false, length = 128)
    private String name;

    /** 拥有者（每工作空间仅一位），拥有最终控制权 */
    @Column(name = "owner_user_id", nullable = false)
    private Long ownerUserId;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Long getOwnerUserId() {
        return ownerUserId;
    }

    public void setOwnerUserId(Long ownerUserId) {
        this.ownerUserId = ownerUserId;
    }
}
