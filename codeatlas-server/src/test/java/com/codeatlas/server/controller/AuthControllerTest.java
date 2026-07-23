package com.codeatlas.server.controller;

import com.codeatlas.server.config.GlobalExceptionHandler;
import com.codeatlas.server.security.JwtAuthFilter;
import com.codeatlas.server.security.JwtTokenProvider;
import com.codeatlas.server.security.UserDetailsServiceImpl;
import com.codeatlas.server.service.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.HashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
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

        when(userService.register(eq("newuser"), eq("123456"), any())).thenReturn(regResult);

        Map<String, String> body = new HashMap<>();
        body.put("username", "newuser");
        body.put("password", "123456");
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
}
