package com.codeatlas.server.controller;

import com.codeatlas.server.config.GlobalExceptionHandler;
import com.codeatlas.server.entity.User;
import com.codeatlas.server.security.CodeAtlasUserDetails;
import com.codeatlas.server.security.JwtAuthFilter;
import com.codeatlas.server.security.JwtTokenProvider;
import com.codeatlas.server.security.TokenBlacklistService;
import com.codeatlas.server.security.UserDetailsServiceImpl;
import com.codeatlas.server.service.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AuthController.class)
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
@Import(GlobalExceptionHandler.class)
@DisplayName("AuthController Web 层测试")
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private UserService userService;

    @MockBean
    private JwtTokenProvider jwtTokenProvider;

    @MockBean
    private JwtAuthFilter jwtAuthFilter;

    @MockBean
    private TokenBlacklistService tokenBlacklistService;

    @MockBean
    private UserDetailsServiceImpl userDetailsService;

    @Test
    @DisplayName("POST /api/v1/auth/login — 登录成功")
    void shouldLoginSuccessfully() throws Exception {
        Map<String, Object> loginResult = new HashMap<>();
        loginResult.put("token", "jwt_test_token");
        loginResult.put("userId", 1L);
        loginResult.put("username", "testuser");
        loginResult.put("role", "DEVELOPER");

        when(userService.login("testuser", "123456")).thenReturn(loginResult);

        Map<String, String> body = Map.of("username", "testuser", "password", "123456");

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.token").value("jwt_test_token"))
                .andExpect(jsonPath("$.data.username").value("testuser"));
    }

    @Test
    @DisplayName("POST /api/v1/auth/login — 参数校验失败返回 400")
    void shouldFailValidation() throws Exception {
        Map<String, String> body = Map.of("username", "", "password", "");

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/v1/auth/register — 注册成功")
    void shouldRegisterSuccessfully() throws Exception {
        Map<String, Object> regResult = new HashMap<>();
        regResult.put("token", "jwt_token");
        regResult.put("username", "newuser");

        when(userService.register(eq("newuser"), eq("Test@12345"), any())).thenReturn(regResult);

        Map<String, String> body = new HashMap<>();
        body.put("username", "newuser");
        body.put("password", "Test@12345");
        body.put("email", "new@test.com");

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.username").value("newuser"));
    }

    @Test
    @DisplayName("POST /api/v1/auth/logout — 登出成功")
    void shouldLogout() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    // ==================== 以下为追加用例（不改动既有方法） ====================

    @Test
    @DisplayName("POST /api/v1/auth/logout — 合法 Bearer token 加入黑名单（精确 token 与过期时间）")
    void shouldBlacklistValidBearerToken() throws Exception {
        when(jwtTokenProvider.validateToken("good.token")).thenReturn(true);
        when(jwtTokenProvider.getExpiresAtMillis("good.token")).thenReturn(1234567890L);

        mockMvc.perform(post("/api/v1/auth/logout")
                        .header("Authorization", "Bearer good.token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verify(tokenBlacklistService).blacklist("good.token", 1234567890L);
    }

    @Test
    @DisplayName("POST /api/v1/auth/logout — token 校验失败时不拉黑")
    void shouldNotBlacklistInvalidToken() throws Exception {
        when(jwtTokenProvider.validateToken("bad.token")).thenReturn(false);

        mockMvc.perform(post("/api/v1/auth/logout")
                        .header("Authorization", "Bearer bad.token"))
                .andExpect(status().isOk());

        verify(tokenBlacklistService, never()).blacklist(anyString(), anyLong());
    }

    @Test
    @DisplayName("POST /api/v1/auth/logout — 非 Bearer 前缀的 Authorization 头被忽略")
    void shouldIgnoreNonBearerAuthorization() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout")
                        .header("Authorization", "Basic dXNlcjpwYXNz"))
                .andExpect(status().isOk());

        verify(jwtTokenProvider, never()).validateToken(anyString());
        verify(tokenBlacklistService, never()).blacklist(anyString(), anyLong());
    }

    @Test
    @DisplayName("POST /api/v1/auth/logout — Bearer 后为空串时跳过校验")
    void shouldSkipEmptyBearerToken() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout")
                        .header("Authorization", "Bearer "))
                .andExpect(status().isOk());

        verify(jwtTokenProvider, never()).validateToken(anyString());
        verify(tokenBlacklistService, never()).blacklist(anyString(), anyLong());
    }

    @Test
    @DisplayName("GET /api/v1/auth/me — 返回当前用户全部字段")
    void shouldReturnCurrentUserProfile() throws Exception {
        User user = new User();
        user.setId(7L);
        user.setUsername("alice");
        user.setEmail("alice@test.com");
        user.setRole("DEVELOPER");
        user.setStatus(1);
        user.setAvatarUrl("https://cdn.test/a.png");
        user.setCreatedAt(LocalDateTime.of(2024, 1, 2, 3, 4, 5));
        when(userService.getCurrentUser(7L)).thenReturn(user);

        mockMvc.perform(get("/api/v1/auth/me")
                        .with(principal(new CodeAtlasUserDetails(7L, "alice", "DEVELOPER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value(7))
                .andExpect(jsonPath("$.data.username").value("alice"))
                .andExpect(jsonPath("$.data.email").value("alice@test.com"))
                .andExpect(jsonPath("$.data.role").value("DEVELOPER"))
                .andExpect(jsonPath("$.data.status").value(1))
                .andExpect(jsonPath("$.data.avatarUrl").value("https://cdn.test/a.png"))
                .andExpect(jsonPath("$.data.createdAt").value("2024-01-02T03:04:05"));
    }

    @Test
    @DisplayName("PUT /api/v1/auth/password — 修改密码透传旧/新密码")
    void shouldChangePassword() throws Exception {
        Map<String, String> body = Map.of("oldPassword", "OldPass1", "newPassword", "NewPass2");

        mockMvc.perform(put("/api/v1/auth/password")
                        .with(principal(new CodeAtlasUserDetails(7L, "alice", "DEVELOPER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verify(userService).changePassword(7L, "OldPass1", "NewPass2");
    }

    @Test
    @DisplayName("PUT /api/v1/auth/password — 新密码过短返回 400 且不调用服务")
    void shouldRejectShortNewPassword() throws Exception {
        Map<String, String> body = Map.of("oldPassword", "OldPass1", "newPassword", "123");

        mockMvc.perform(put("/api/v1/auth/password")
                        .with(principal(new CodeAtlasUserDetails(7L, "alice", "DEVELOPER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest());

        verify(userService, never()).changePassword(anyLong(), anyString(), anyString());
    }

    /**
     * addFilters=false 时 SecurityMockMvcRequestPostProcessors.user() 只写请求属性、
     * 不经 SecurityContextPersistenceFilter 进入 SecurityContextHolder，@AuthenticationPrincipal
     * 会解析为 null；此处直接写入 SecurityContextHolder（与 MockMvc 同线程执行）。
     */
    private static RequestPostProcessor principal(CodeAtlasUserDetails details) {
        return request -> {
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(
                    new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities()));
            SecurityContextHolder.setContext(context);
            return request;
        };
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }
}
