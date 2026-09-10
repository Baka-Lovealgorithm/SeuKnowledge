package com.ai.konwledgerepo.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记只能由平台管理员访问的接口。
 * <p>
 * 平台管理员身份来自 {@code sys_user.role=ADMIN}，与当前工作空间中的 OWNER / ADMIN
 * 成员角色严格分离；由 {@link AuthInterceptor} 直接校验，不能以工作空间角色替代。
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface PlatformAdminOnly {
}
