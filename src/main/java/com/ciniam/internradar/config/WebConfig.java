package com.ciniam.internradar.config;

import com.ciniam.internradar.security.AuthInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 注册登录拦截器。登录入口、接口文档与健康检查不需要凭证
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;

    public WebConfig(AuthInterceptor authInterceptor) {
        this.authInterceptor = authInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns(
                        "/auth/users",
                        "/auth/register",
                        "/auth/login",
                        "/auth/dev-login",
                        "/auth/password/forgot",
                        "/auth/password/reset",
                        "/auth/feishu/url",
                        // 登录回调由浏览器直接跳转，带不上登录凭证，必须放行
                        "/auth/feishu/callback",
                        "/feishu/oauth/callback",
                        "/health",
                        "/error",
                        "/doc.html",
                        "/webjars/**",
                        "/swagger-ui/**",
                        "/swagger-ui.html",
                        "/swagger-resources/**",
                        "/v3/api-docs/**");
    }
}
