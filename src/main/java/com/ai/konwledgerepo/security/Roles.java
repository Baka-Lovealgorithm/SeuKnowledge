package com.ai.konwledgerepo.security;

import com.ai.konwledgerepo.entity.WorkspaceMember;

/**
 * 角色常量：直接引用 {@link WorkspaceMember.ROLE_*} 作为唯一取值源，
 * 避免两套字面量各自维护产生漂移（历史上为独立字面量）。
 * 供注解单元素写法（{@code @RequireRole(Roles.OWNER)}）与 {@code SysUser.role} 使用。
 */
public final class Roles {

    private Roles() {
    }

    public static final String OWNER = WorkspaceMember.ROLE_OWNER;
    public static final String ADMIN = WorkspaceMember.ROLE_ADMIN;
    public static final String EDITOR = WorkspaceMember.ROLE_EDITOR;
    public static final String MEMBER = WorkspaceMember.ROLE_MEMBER;
}
