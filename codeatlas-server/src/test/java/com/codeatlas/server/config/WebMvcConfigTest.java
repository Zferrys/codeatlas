package com.codeatlas.server.config;

import com.codeatlas.server.security.CodeAtlasUserDetails;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.core.MethodParameter;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.handler.MappedInterceptor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.verify;

@DisplayName("WebMvcConfig Web 层配置测试")
class WebMvcConfigTest {

    private final WebMvcConfig config = new WebMvcConfig();

    @AfterEach
    void tearDown() {
        MDC.clear();
        SecurityContextHolder.clearContext();
    }

    /** 暴露 protected 的 getInterceptors 并记录每次 addInterceptor 返回的注册对象（用于断言 order） */
    private static final class CapturingRegistry extends InterceptorRegistry {
        private final List<InterceptorRegistration> registrations = new ArrayList<>();

        @Override
        public InterceptorRegistration addInterceptor(HandlerInterceptor interceptor) {
            InterceptorRegistration registration = super.addInterceptor(interceptor);
            registrations.add(registration);
            return registration;
        }

        @Override
        public List<Object> getInterceptors() {
            return super.getInterceptors();
        }
    }

    @SuppressWarnings("unused")
    public static class ProbeMethods {
        public void withUser(CodeAtlasUserDetails user) {
        }

        public void withString(String value) {
        }
    }

    // ========== 拦截器注册 ==========

    @Test
    @DisplayName("trace 拦截器：透传 X-Trace-Id 到 MDC 与响应头，afterCompletion 清理 MDC")
    void traceInterceptor_shouldPropagateExistingTraceId() throws Exception {
        CapturingRegistry registry = new CapturingRegistry();
        config.addInterceptors(registry);

        HandlerInterceptor traceInterceptor = assertInstanceOf(HandlerInterceptor.class, registry.getInterceptors().get(0));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Trace-Id", "abc123def456");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(traceInterceptor.preHandle(request, response, new Object()));
        assertEquals("abc123def456", MDC.get("traceId"));
        assertEquals("abc123def456", response.getHeader("X-Trace-Id"));

        traceInterceptor.afterCompletion(request, response, new Object(), null);
        assertNull(MDC.get("traceId"), "afterCompletion 必须清理 MDC，避免线程池串号");
    }

    @Test
    @DisplayName("trace 拦截器：缺失或空白 X-Trace-Id 时生成 12 位十六进制 traceId")
    void traceInterceptor_shouldGenerateTraceIdWhenMissing() throws Exception {
        CapturingRegistry registry = new CapturingRegistry();
        config.addInterceptors(registry);
        HandlerInterceptor traceInterceptor = (HandlerInterceptor) registry.getInterceptors().get(0);

        MockHttpServletRequest noHeader = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertTrue(traceInterceptor.preHandle(noHeader, response, new Object()));
        String generated = MDC.get("traceId");
        assertTrue(generated.matches("[0-9a-f]{12}"), "应生成 12 位十六进制 traceId，实际=" + generated);
        assertEquals(generated, response.getHeader("X-Trace-Id"));
        MDC.clear();

        MockHttpServletRequest emptyHeader = new MockHttpServletRequest();
        emptyHeader.addHeader("X-Trace-Id", "");
        MockHttpServletResponse response2 = new MockHttpServletResponse();
        assertTrue(traceInterceptor.preHandle(emptyHeader, response2, new Object()));
        String regenerated = MDC.get("traceId");
        assertTrue(regenerated.matches("[0-9a-f]{12}"), "空字符串头也应生成新 traceId");
    }

    @Test
    @DisplayName("注册 4 个拦截器：trace(1) / 登录限流10(2) / 扫描限流20(3) / 通用限流60(4)")
    void addInterceptors_shouldRegisterFourInterceptorsWithExactLimitsAndOrder() {
        CapturingRegistry registry = new CapturingRegistry();

        config.addInterceptors(registry);

        List<Object> interceptors = registry.getInterceptors();
        assertEquals(4, interceptors.size());
        assertEquals(4, registry.registrations.size());
        assertEquals(1, ReflectionTestUtils.getField(registry.registrations.get(0), "order"));
        assertEquals(2, ReflectionTestUtils.getField(registry.registrations.get(1), "order"));
        assertEquals(3, ReflectionTestUtils.getField(registry.registrations.get(2), "order"));
        assertEquals(4, ReflectionTestUtils.getField(registry.registrations.get(3), "order"));

        assertInstanceOf(HandlerInterceptor.class, interceptors.get(0));

        MappedInterceptor login = assertInstanceOf(MappedInterceptor.class, interceptors.get(1));
        RateLimitInterceptor loginLimiter = assertInstanceOf(RateLimitInterceptor.class, login.getInterceptor());
        assertEquals(10, ReflectionTestUtils.getField(loginLimiter, "maxRequests"));
        assertArrayEquals(new String[]{"/api/v1/auth/login"}, login.getIncludePathPatterns());
        assertTrue(login.getExcludePathPatterns() == null || login.getExcludePathPatterns().length == 0,
                "登录限流只声明包含路径，不应有排除路径");

        MappedInterceptor scans = assertInstanceOf(MappedInterceptor.class, interceptors.get(2));
        RateLimitInterceptor scanLimiter = assertInstanceOf(RateLimitInterceptor.class, scans.getInterceptor());
        assertEquals(20, ReflectionTestUtils.getField(scanLimiter, "maxRequests"));
        assertArrayEquals(new String[]{"/api/v1/projects/*/scans"}, scans.getIncludePathPatterns());
        assertArrayEquals(new String[]{"/api/v1/projects/*/scans/progress"}, scans.getExcludePathPatterns());

        MappedInterceptor api = assertInstanceOf(MappedInterceptor.class, interceptors.get(3));
        RateLimitInterceptor apiLimiter = assertInstanceOf(RateLimitInterceptor.class, api.getInterceptor());
        assertEquals(60, ReflectionTestUtils.getField(apiLimiter, "maxRequests"));
        assertArrayEquals(new String[]{"/api/**"}, api.getIncludePathPatterns());
        assertArrayEquals(new String[]{"/api/v1/auth/login", "/api/v1/projects/*/scans"},
                api.getExcludePathPatterns());
    }

    @Test
    @DisplayName("Redis 可用时把 RedisRateLimiter 注入限流拦截器（Redis 优先，返回1放行）")
    void addInterceptors_withRedis_shouldWireRedisRateLimiter() throws Exception {
        WebMvcConfig redisConfig = new WebMvcConfig();
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ReflectionTestUtils.setField(redisConfig, "redisTemplate", redisTemplate);
        doReturn(1L).when(redisTemplate).execute(any(RedisScript.class), anyList(), anyString(), anyString(), anyString());

        CapturingRegistry registry = new CapturingRegistry();
        redisConfig.addInterceptors(registry);

        RateLimitInterceptor loginLimiter = (RateLimitInterceptor)
                ((MappedInterceptor) registry.getInterceptors().get(1)).getInterceptor();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/v1/auth/login");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(loginLimiter.preHandle(request, response, new Object()));
        verify(redisTemplate).execute(any(RedisScript.class), anyList(), anyString(), anyString(), anyString());
    }

    // ========== 参数解析器 ==========

    @Test
    @DisplayName("参数解析器：仅支持 CodeAtlasUserDetails 类型，从 SecurityContext 取 principal")
    void argumentResolver_shouldResolveCurrentUserFromSecurityContext() throws Exception {
        List<HandlerMethodArgumentResolver> resolvers = new ArrayList<>();

        config.addArgumentResolvers(resolvers);

        assertEquals(1, resolvers.size());
        HandlerMethodArgumentResolver resolver = resolvers.get(0);
        MethodParameter userParam = new MethodParameter(
                ProbeMethods.class.getMethod("withUser", CodeAtlasUserDetails.class), 0);
        MethodParameter stringParam = new MethodParameter(
                ProbeMethods.class.getMethod("withString", String.class), 0);

        assertTrue(resolver.supportsParameter(userParam));
        assertFalse(resolver.supportsParameter(stringParam));

        CodeAtlasUserDetails details = new CodeAtlasUserDetails(3L, "alice", "ADMIN");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities()));
        assertSame(details, resolver.resolveArgument(userParam, null, null, null));
    }

    @Test
    @DisplayName("参数解析器：未认证或 principal 非 CodeAtlasUserDetails 时返回 null")
    void argumentResolver_shouldReturnNullWithoutAuthenticatedUser() throws Exception {
        List<HandlerMethodArgumentResolver> resolvers = new ArrayList<>();
        config.addArgumentResolvers(resolvers);
        HandlerMethodArgumentResolver resolver = resolvers.get(0);
        MethodParameter userParam = new MethodParameter(
                ProbeMethods.class.getMethod("withUser", CodeAtlasUserDetails.class), 0);

        assertNull(resolver.resolveArgument(userParam, null, null, null));

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("raw-string-principal", null));
        assertNull(resolver.resolveArgument(userParam, null, null, null));
    }

    // ========== 尾部斜杠过滤器 ==========

    @Test
    @DisplayName("尾部斜杠过滤器：注册到 /*、order=-101（先于 Spring Security）")
    void trailingSlashFilter_shouldRegisterBeforeSecurity() {
        FilterRegistrationBean<Filter> bean = config.trailingSlashFilter();

        assertEquals(Collections.singleton("/api/*"), bean.getUrlPatterns());
        assertEquals(-101, bean.getOrder());
    }

    @Test
    @DisplayName("尾部斜杠过滤器：重写 URI 并重建带端口的完整 URL")
    void trailingSlashFilter_shouldStripTrailingSlash() throws Exception {
        Filter filter = config.trailingSlashFilter().getFilter();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/v1/projects/");
        request.setScheme("https");
        request.setServerName("example.com");
        request.setServerPort(8443);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        ArgumentCaptor<HttpServletRequest> captor = ArgumentCaptor.forClass(HttpServletRequest.class);
        verify(chain).doFilter(captor.capture(), same(response));
        HttpServletRequest forwarded = captor.getValue();
        assertEquals("/api/v1/projects", forwarded.getRequestURI());
        assertEquals("https://example.com:8443/api/v1/projects", forwarded.getRequestURL().toString());
    }

    @Test
    @DisplayName("尾部斜杠过滤器：80/443 端口不出现在重建的 URL 中")
    void trailingSlashFilter_shouldOmitDefaultPorts() throws Exception {
        Filter filter = config.trailingSlashFilter().getFilter();

        MockHttpServletRequest https = new MockHttpServletRequest();
        https.setRequestURI("/api/v1/projects/");
        https.setScheme("https");
        https.setServerName("example.com");
        https.setServerPort(443);
        FilterChain chain443 = mock(FilterChain.class);
        filter.doFilter(https, new MockHttpServletResponse(), chain443);
        ArgumentCaptor<HttpServletRequest> captor443 = ArgumentCaptor.forClass(HttpServletRequest.class);
        verify(chain443).doFilter(captor443.capture(), any(ServletResponse.class));
        assertEquals("https://example.com/api/v1/projects", captor443.getValue().getRequestURL().toString());

        MockHttpServletRequest http = new MockHttpServletRequest();
        http.setRequestURI("/api/v1/projects/");
        http.setScheme("http");
        http.setServerName("example.com");
        http.setServerPort(80);
        FilterChain chain80 = mock(FilterChain.class);
        filter.doFilter(http, new MockHttpServletResponse(), chain80);
        ArgumentCaptor<HttpServletRequest> captor80 = ArgumentCaptor.forClass(HttpServletRequest.class);
        verify(chain80).doFilter(captor80.capture(), any(ServletResponse.class));
        assertEquals("http://example.com/api/v1/projects", captor80.getValue().getRequestURL().toString());
    }

    @Test
    @DisplayName("尾部斜杠过滤器：无尾部斜杠的 URI 原样透传（同一请求对象）")
    void trailingSlashFilter_shouldPassThroughPlainUri() throws Exception {
        Filter filter = config.trailingSlashFilter().getFilter();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/v1/projects");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        ArgumentCaptor<HttpServletRequest> captor = ArgumentCaptor.forClass(HttpServletRequest.class);
        verify(chain).doFilter(captor.capture(), same(response));
        assertSame(request, captor.getValue(), "无尾部斜杠时不应包装请求");
        assertEquals("/api/v1/projects", captor.getValue().getRequestURI());
    }

    @Test
    @DisplayName("尾部斜杠过滤器：根路径 '/' 不被截断为空串")
    void trailingSlashFilter_shouldNotRewriteRootPath() throws Exception {
        Filter filter = config.trailingSlashFilter().getFilter();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        ArgumentCaptor<HttpServletRequest> captor = ArgumentCaptor.forClass(HttpServletRequest.class);
        verify(chain).doFilter(captor.capture(), same(response));
        assertSame(request, captor.getValue());
        assertEquals("/", captor.getValue().getRequestURI());
    }
}
