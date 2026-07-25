package com.codeatlas.server.config;

import com.codeatlas.server.security.CodeAtlasUserDetails;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
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

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
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

        // 扫描端点限流：20 次/分钟（扫描本身有互斥保护，限流仅防滥用）
        registry.addInterceptor(new RateLimitInterceptor(20, redisRateLimiter))
                .addPathPatterns("/api/v1/projects/*/scans")
                .excludePathPatterns("/api/v1/projects/*/scans/progress")
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

    /**
     * 去掉 API 请求路径末尾的 /，兼容 Spring Boot 3.x。
     * Spring Framework 6 默认不再将 /api/v1/projects/ 匹配到 /api/v1/projects，
     * 导致前端请求尾部带斜杠时返回 404 → axios 拦截器弹出"资源不存在"。
     * 用 request wrapper 静默改写，不影响 POST/PUT 等请求方法。
     */
    @Bean
    public FilterRegistrationBean<Filter> trailingSlashFilter() {
        Filter filter = (ServletRequest request, ServletResponse response, FilterChain chain)
                -> {
            HttpServletRequest req = (HttpServletRequest) request;
            String uri = req.getRequestURI();
            if (uri.length() > 1 && uri.endsWith("/")) {
                String stripped = uri.substring(0, uri.length() - 1);
                String scheme = req.getScheme();
                String serverName = req.getServerName();
                int port = req.getServerPort();
                HttpServletRequest original = req;
                req = new jakarta.servlet.http.HttpServletRequestWrapper(original) {
                    @Override
                    public String getRequestURI() { return stripped; }
                    @Override
                    public StringBuffer getRequestURL() {
                        return new StringBuffer(scheme + "://" + serverName
                                + ((port == 80 || port == 443) ? "" : ":" + port)
                                + stripped);
                    }
                };
            }
            chain.doFilter(req, response);
        };
        FilterRegistrationBean<Filter> bean = new FilterRegistrationBean<>(filter);
        bean.addUrlPatterns("/api/*");
        bean.setOrder(-101); // 在 Spring Security 之前执行
        return bean;
    }
}
