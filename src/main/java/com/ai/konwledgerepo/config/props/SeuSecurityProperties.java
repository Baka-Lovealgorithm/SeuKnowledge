package com.ai.konwledgerepo.config.props;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/**
 * seuknowledge.security.* 配置。
 *
 * @param tokenTtlSeconds   登录 token 有效期（秒），默认 7 天
 * @param adminUsername     内置管理员用户名（默认 admin，可配置）
 * @param adminPassword     内置管理员初始密码（默认 admin123，生产建议环境变量覆盖）
 * @param corsAllowedOrigins CORS 允许的来源（默认 * 全开，生产建议收紧为白名单）
 */
@ConfigurationProperties(prefix = "seuknowledge.security")
public record SeuSecurityProperties(
        @DefaultValue("604800") long tokenTtlSeconds,
        @DefaultValue("admin") String adminUsername,
        @DefaultValue("admin123") String adminPassword,
        @DefaultValue("*") List<String> corsAllowedOrigins) {
}
