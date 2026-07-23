package com.codeatlas.server.config;

import com.codeatlas.server.security.CodeAtlasUserDetails;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.MethodParameter;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.UUID;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Autowired(required = false)
    private StringRedisTemplate redisTemplate;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // Redis 分布式限流器（Redis 可用时优先使用）
        RedisRateLimiter redisRateLimiter = null;
        if (redisTemplate != null) {
            redisRateLimiter = new RedisRateLimiter(redisTemplate);
        }

        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request,
                                     HttpServletResponse response,
                                     Object handler) {
                String traceId = request.getHeader("X-Trace-Id");
                if (traceId == null || traceId.isEmpty()) {
                    traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
                }
                MDC.put("traceId", traceId);
                response.setHeader("X-Trace-Id", traceId);
                return true;
            }

            @Override
            public void afterCompletion(HttpServletRequest request,
                                        HttpServletResponse response,
                                        Object handler, Exception ex) {
                MDC.clear();
            }
        }).order(1);

        // 登录端点限流：10 次/分钟
        registry.addInterceptor(new RateLimitInterceptor(10, redisRateLimiter))
                .addPathPatterns("/api/v1/auth/login")
                .order(2);

        // AI 端点限流：5 次/分钟
        registry.addInterceptor(new RateLimitInterceptor(5, redisRateLimiter))
                .addPathPatterns("/api/v1/projects/*/scans")
                .order(3);

        // 通用 API 限流：60 次/分钟
        registry.addInterceptor(new RateLimitInterceptor(60, redisRateLimiter))
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/v1/auth/login", "/api/v1/projects/*/scans")
                .order(4);
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new HandlerMethodArgumentResolver() {
            @Override
            public boolean supportsParameter(MethodParameter parameter) {
                return parameter.getParameterType().equals(CodeAtlasUserDetails.class);
            }

            @Override
            public Object resolveArgument(MethodParameter parameter,
                                          ModelAndViewContainer mavContainer,
                                          NativeWebRequest webRequest,
                                          WebDataBinderFactory binderFactory) {
                org.springframework.security.core.Authentication auth = SecurityContextHolder.getContext().getAuthentication();
                if (auth != null && auth.getPrincipal() instanceof CodeAtlasUserDetails) {
                    return auth.getPrincipal();
                }
                return null;
            }
        });
    }
}
