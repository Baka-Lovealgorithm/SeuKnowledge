package com.ai.konwledgerepo.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 角色权限注解：标注在 Controller 类或方法上，声明可访问的角色集合。
 * 方法级注解优先于类级。未标注 = 登录即可访问（MEMBER 及以上）。
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireRole {

    /** 允许访问的角色：OWNER / ADMIN / EDITOR / MEMBER */
    String[] value();
}
