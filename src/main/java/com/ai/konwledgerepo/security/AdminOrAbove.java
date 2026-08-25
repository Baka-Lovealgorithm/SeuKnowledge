package com.ai.konwledgerepo.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 元注解：OWNER / ADMIN 可执行（替代散落各 Controller 的
 * {@code @RequireRole({"OWNER", "ADMIN"})} 字面量）。
 * AuthInterceptor 需用 AnnotatedElementUtils 解析元注解。
 */
@RequireRole({"OWNER", "ADMIN"})
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface AdminOrAbove {
}
