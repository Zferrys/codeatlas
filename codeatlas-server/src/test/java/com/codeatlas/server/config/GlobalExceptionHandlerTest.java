package com.codeatlas.server.config;

import com.codeatlas.common.constant.ErrorCode;
import com.codeatlas.common.exception.BusinessException;
import com.codeatlas.server.controller.AuthController;
import com.codeatlas.server.security.JwtAuthFilter;
import com.codeatlas.server.security.JwtTokenProvider;
import com.codeatlas.server.security.TokenBlacklistService;
import com.codeatlas.server.security.UserDetailsServiceImpl;
import com.codeatlas.server.service.UserService;
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

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest({AuthController.class, ClientErrorProbeController.class})
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
@Import(GlobalExceptionHandler.class)
@DisplayName("全局异常处理器测试")
class GlobalExceptionHandlerTest {

    @Autowired
    private MockMvc mockMvc;

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
    @DisplayName("BusinessException 返回对应错误码")
    void shouldHandleBusinessException() throws Exception {
        when(userService.login("bad", "bad"))
                .thenThrow(new BusinessException(ErrorCode.LOGIN_FAILED));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"bad\",\"password\":\"bad\"}"))
                .andExpect(status().is4xxClientError())
                .andExpect(jsonPath("$.code").value(ErrorCode.LOGIN_FAILED.getCode()));
    }

    @Test
    @DisplayName("参数校验失败返回 400")
    void shouldHandleValidation() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"\",\"password\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.BAD_REQUEST.getCode()));
    }

    @Test
    @DisplayName("请求体畸形 JSON 返回 400 而非 500")
    void shouldHandleMalformedJsonBody() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("not-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.BAD_REQUEST.getCode()));
    }

    @Test
    @DisplayName("路径参数类型错误返回 400 而非 500")
    void shouldHandleTypeMismatch() throws Exception {
        mockMvc.perform(get("/api/v1/probe/types/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.BAD_REQUEST.getCode()));
    }

    @Test
    @DisplayName("缺少必需请求参数返回 400 而非 500")
    void shouldHandleMissingParameter() throws Exception {
        mockMvc.perform(get("/api/v1/probe/params"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.BAD_REQUEST.getCode()));
    }

    @Test
    @DisplayName("不支持的请求体类型返回 415 而非 500")
    void shouldHandleUnsupportedMediaType() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("not-json"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value(ErrorCode.BAD_REQUEST.getCode()));
    }

    @Test
    @DisplayName("BusinessException(BAD_REQUEST) 返回真实 HTTP 400 而非 200")
    void shouldHandleBusinessExceptionWithBadRequestAsHttp400() throws Exception {
        mockMvc.perform(get("/api/v1/probe/business"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.BAD_REQUEST.getCode()))
                .andExpect(jsonPath("$.message").value("本地源码路径无效或不存在"));
    }
}
