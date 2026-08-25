package com.ai.konwledgerepo.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 全局共享的 PasswordEncoder bean（BCrypt），供认证、账号初始化、成员管理等复用，
 * 消除各处 new BCryptPasswordEncoder() 的重复实例。
 */
@Configuration
public class PasswordEncoderConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
