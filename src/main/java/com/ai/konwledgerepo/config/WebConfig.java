package com.ai.konwledgerepo.config;

import com.ai.konwledgerepo.config.props.SeuSecurityProperties;
import com.ai.konwledgerepo.security.AuthInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 配置：登录态拦截器 + CORS（来源见 seuknowledge.security.cors-allowed-origins，默认 * 全开，生产建议收紧白名单）。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;
    private final SeuSecurityProperties securityProps;

    public WebConfig(AuthInterceptor authInterceptor, SeuSecurityProperties securityProps) {
        this.authInterceptor = authInterceptor;
        this.securityProps = securityProps;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/auth/login", "/api/auth/logout", "/error");
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns(securityProps.corsAllowedOrigins().toArray(new String[0]))
                .allowedMethods("*")
                .allowedHeaders("*")
                .allowCredentials(true);
    }
}
