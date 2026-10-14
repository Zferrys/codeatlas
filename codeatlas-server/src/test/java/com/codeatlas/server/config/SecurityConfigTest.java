package com.codeatlas.server.config;

import com.codeatlas.server.security.JwtAuthFilter;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = SecurityConfigTest.SecurityProbeController.class)
@ActiveProfiles("test")
@Import({SecurityConfig.class, SecurityConfigTest.SecurityProbeController.class})
@DisplayName("SecurityConfig 安全过滤链测试")
class SecurityConfigTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private FilterChainProxy springSecurityFilterChain;

    @MockBean
    private JwtAuthFilter jwtAuthFilter;

    @Autowired(required = false)
    private SecurityFilterChain securityFilterChainBean;

    @Autowired
    private ApplicationContext applicationContext;

    private final SecurityConfig config = new SecurityConfig(null);

    @BeforeEach
    void passThroughJwtFilter() throws Exception {
        doAnswer(invocation -> {
            FilterChain chain = invocation.getArgument(2);
            chain.doFilter(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(jwtAuthFilter).doFilter(any(), any(), any());
    }

    @RestController
    public static class SecurityProbeController {

        @GetMapping("/")
        public String root() {
            return "root";
        }

        @GetMapping("/api/v1/auth/ping")
        public String authPing() {
            return "auth";
        }

        @GetMapping("/api/v1/telemetry/ping")
        public String telemetryPing() {
            return "telemetry";
        }

        @GetMapping("/api/v1/secure/ping")
        public String securePing() {
            return "secure-get";
        }

        @PostMapping("/api/v1/secure/ping")
        public String securePost() {
            return "secure-post";
        }

        @GetMapping("/doc.html")
        public String docHtml() {
            return "doc";
        }

        @GetMapping("/swagger-ui/index.html")
        public String swaggerUi() {
            return "swagger";
        }

        @GetMapping("/v3/api-docs/swagger.json")
        public String apiDocs() {
            return "api-docs";
        }

        @GetMapping("/webjars/app.js")
        public String webjars() {
            return "webjars";
        }

        @GetMapping("/index.html")
        public String index() {
            return "index";
        }

        @GetMapping("/favicon.ico")
        public String faviconIco() {
            return "favicon-ico";
        }

        @GetMapping("/favicon.svg")
        public String faviconSvg() {
            return "favicon-svg";
        }

        @GetMapping("/assets/app.js")
        public String assets() {
            return "assets";
        }

        @GetMapping("/actuator/health/liveness")
        public String health() {
            return "health";
        }
    }

    // ========== 过滤链结构 ==========

    @Test
    @DisplayName("filterChain(HttpSecurity) 必须返回非 null 的 SecurityFilterChain Bean")
    void filterChain_shouldRegisterNonNullSecurityFilterChainBean() {
        assertNotNull(securityFilterChainBean,
                "SecurityConfig.filterChain 的返回值必须注册为 SecurityFilterChain Bean（http.build() 结果不可为 null）");
    }

    @Test
    @DisplayName("直接调用 filterChain(HttpSecurity) 必须返回非 null 链（http.build() 结果不可为 null）")
    void filterChain_calledDirectly_shouldReturnNonNullChain() throws Exception {
        // filterChain 仅在 Spring 上下文刷新时被调用；PIT 单 minion JVM 内复用 Spring 上下文缓存，
        // 刷新期代码在缓存命中时不再重放，上下文路径无法察觉该方法内部的变异。
        // 直接以原型 Bean HttpSecurity 调用，可在任意缓存状态下覆盖 http.build() 的返回值。
        SecurityConfig configUnderTest = new SecurityConfig(jwtAuthFilter);
        HttpSecurity http = applicationContext.getBean(HttpSecurity.class);

        SecurityFilterChain chain = configUnderTest.filterChain(http);

        assertNotNull(chain, "filterChain(http) 必须返回 http.build() 构建的 SecurityFilterChain");
    }

    @Test
    @DisplayName("JwtAuthFilter 位于 AuthorizationFilter 之前（先认证后鉴权）")
    void jwtFilterShouldPrecedeAuthorizationFilter() {
        List<Filter> filters = springSecurityFilterChain.getFilters("/api/v1/secure/ping");

        int jwtIndex = filters.indexOf(jwtAuthFilter);
        int authorizationIndex = -1;
        for (int i = 0; i < filters.size(); i++) {
            if (filters.get(i) instanceof AuthorizationFilter) {
                authorizationIndex = i;
                break;
            }
        }
        assertTrue(jwtIndex >= 0, "JwtAuthFilter 必须存在于过滤链中");
        assertTrue(authorizationIndex >= 0, "过滤链应包含 AuthorizationFilter");
        assertTrue(jwtIndex < authorizationIndex, "JwtAuthFilter 必须在 AuthorizationFilter 之前执行");
    }

    // ========== permitAll 白名单 ==========

    @ParameterizedTest
    @ValueSource(strings = {
            "/",
            "/api/v1/auth/ping",
            "/api/v1/telemetry/ping",
            "/doc.html",
            "/swagger-ui/index.html",
            "/v3/api-docs/swagger.json",
            "/webjars/app.js",
            "/index.html",
            "/favicon.ico",
            "/favicon.svg",
            "/assets/app.js",
            "/actuator/health/liveness"
    })
    @DisplayName("permitAll 路径未认证即可访问（返回 200 而非 403）")
    void permitAllPaths_shouldBeAccessibleWithoutAuthentication(String path) throws Exception {
        mockMvc.perform(get(path))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("非白名单路径未认证一律 403（anyRequest().authenticated()）")
    void protectedPath_shouldRejectAnonymousRequest() throws Exception {
        mockMvc.perform(get("/api/v1/secure/ping"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("认证用户可访问受保护路径")
    @WithMockUser(username = "tester", roles = "USER")
    void protectedPath_shouldAllowAuthenticatedRequest() throws Exception {
        mockMvc.perform(get("/api/v1/secure/ping"))
                .andExpect(status().isOk())
                .andExpect(content().string("secure-get"));
    }

    @Test
    @DisplayName("认证用户 POST 无 CSRF token 也能成功（csrf 已禁用）")
    @WithMockUser(username = "tester", roles = "USER")
    void postWithoutCsrfToken_shouldSucceedWhenAuthenticated() throws Exception {
        mockMvc.perform(post("/api/v1/secure/ping"))
                .andExpect(status().isOk())
                .andExpect(content().string("secure-post"));
    }

    @Test
    @DisplayName("未认证 POST 被拒绝（403）")
    void postWithoutAuth_shouldBeRejected() throws Exception {
        mockMvc.perform(post("/api/v1/secure/ping"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("ERROR/ASYNC 派发类型放行（/error 转发与异步续处理不触发 403）")
    void errorAndAsyncDispatches_shouldBePermitted() throws Exception {
        mockMvc.perform(get("/api/v1/secure/ping")
                        .with(request -> {
                            ((MockHttpServletRequest) request).setDispatcherType(DispatcherType.ERROR);
                            return request;
                        }))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/secure/ping")
                        .with(request -> {
                            ((MockHttpServletRequest) request).setDispatcherType(DispatcherType.ASYNC);
                            return request;
                        }))
                .andExpect(status().isOk());
    }

    // ========== 会话与 CORS / 密码编码 ==========

    @Test
    @DisplayName("无状态会话：受保护请求不产生 JSESSIONID Cookie")
    void statelessSession_shouldNotIssueSessionCookie() throws Exception {
        mockMvc.perform(get("/api/v1/secure/ping"))
                .andExpect(result -> assertNull(result.getResponse().getCookie("JSESSIONID")));
    }

    @Test
    @DisplayName("passwordEncoder 返回 BCrypt，且编码结果非明文、可校验")
    void passwordEncoder_shouldUseBcrypt() {
        PasswordEncoder encoder = config.passwordEncoder();

        assertInstanceOf(BCryptPasswordEncoder.class, encoder);
        String hash = encoder.encode("s3cret-password");
        assertNotNull(hash);
        assertFalse(hash.contains("s3cret-password"), "编码结果不得包含明文");
        assertTrue(hash.startsWith("$2"), "BCrypt 哈希应以 $2 前缀开头");
        assertTrue(encoder.matches("s3cret-password", hash));
        assertFalse(encoder.matches("wrong-password", hash));
    }

    @Test
    @DisplayName("CORS 配置：默认来源模式、方法白名单、任意请求头、允许凭证")
    void corsConfiguration_shouldMatchDefaultSettings() {
        assumeTrue(System.getenv("CORS_ORIGINS") == null, "环境变量 CORS_ORIGINS 已设置，跳过默认值断言");
        CorsConfigurationSource source = config.corsConfigurationSource();

        CorsConfiguration cors = source.getCorsConfiguration(new MockHttpServletRequest("GET", "/api/x"));
        assertNotNull(cors);
        assertEquals(Arrays.asList("http://localhost:5173", "http://localhost:8080"),
                cors.getAllowedOriginPatterns());
        assertEquals(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS"), cors.getAllowedMethods());
        assertEquals(Arrays.asList("*"), cors.getAllowedHeaders());
        assertEquals(Boolean.TRUE, cors.getAllowCredentials());
        assertNotNull(source.getCorsConfiguration(new MockHttpServletRequest("GET", "/other/path")),
                "CORS 规则应覆盖 /** 所有路径");
    }

}
