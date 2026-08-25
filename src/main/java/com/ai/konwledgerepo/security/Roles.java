package com.ai.konwledgerepo.security;

/**
 * 角色常量（与 WorkspaceMember.ROLE_* 对齐，供注解单元素写法与 SysUser.role 使用）。
 */
public final class Roles {

    private Roles() {
    }

    public static final String OWNER = "OWNER";
    public static final String ADMIN = "ADMIN";
    public static final String EDITOR = "EDITOR";
    public static final String MEMBER = "MEMBER";
}
