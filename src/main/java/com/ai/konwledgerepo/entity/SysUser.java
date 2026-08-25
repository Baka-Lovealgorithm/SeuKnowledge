package com.ai.konwledgerepo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * 系统用户。一期仅管理员角色。
 */
@Entity
@Table(name = "sys_user")
public class SysUser extends BaseEntity {

    @Column(nullable = false, unique = true, length = 64)
    private String username;

    /** BCrypt 加密后的密码 */
    @Column(nullable = false, length = 255)
    private String password;

    /** 角色：一期仅 ADMIN */
    @Column(nullable = false, length = 20)
    private String role;

    @Column(nullable = false)
    private Boolean enabled = true;

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }
}
