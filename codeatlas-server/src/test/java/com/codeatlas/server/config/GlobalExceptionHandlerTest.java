package com.codeatlas.server.config;

import com.codeatlas.common.constant.ErrorCode;
import com.codeatlas.common.dto.ApiResponse;
import com.codeatlas.common.exception.AiException;
import com.codeatlas.common.exception.BusinessException;
import com.codeatlas.server.controller.AuthController;
import com.codeatlas.server.security.JwtAuthFilter;
import com.codeatlas.server.security.JwtTokenProvider;
import com.codeatlas.server.security.TokenBlacklistService;
import com.codeatlas.server.security.UserDetailsServiceImpl;
import com.codeatlas.server.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    // ========== 以下为直接调用处理器方法的补充用例 ==========

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    @DisplayName("AccessDeniedException 映射为 HTTP 403 + FORBIDDEN 错误码")
    void direct_shouldHandleAccessDeniedAs403() {
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleAccessDenied(new AccessDeniedException("denied"));

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertEquals(ErrorCode.FORBIDDEN.getCode(), response.getBody().getCode());
        assertEquals(ErrorCode.FORBIDDEN.getMessage(), response.getBody().getMessage());
    }

    @Test
    @DisplayName("不含方法的请求映射为 HTTP 405 + 携带方法名的提示")
    void direct_shouldHandleMethodNotSupportedAs405() {
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleMethodNotSupported(new HttpRequestMethodNotSupportedException("PATCH"));

        assertEquals(HttpStatus.METHOD_NOT_ALLOWED, response.getStatusCode());
        assertEquals(ErrorCode.BAD_REQUEST.getCode(), response.getBody().getCode());
        assertEquals("不支持的请求方法: PATCH", response.getBody().getMessage());
    }

    @Test
    @DisplayName("mapHttpStatus：UNAUTHORIZED 码映射 HTTP 401")
    void direct_shouldMapUnauthorizedCodeTo401() {
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleBusiness(new BusinessException(ErrorCode.UNAUTHORIZED));

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }

    @Test
    @DisplayName("mapHttpStatus：FORBIDDEN 码映射 HTTP 403")
    void direct_shouldMapForbiddenCodeTo403() {
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleBusiness(new BusinessException(ErrorCode.FORBIDDEN));

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
    }

    @Test
    @DisplayName("mapHttpStatus：NOT_FOUND 码映射 HTTP 404")
    void direct_shouldMapNotFoundCodeTo404() {
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleBusiness(new BusinessException(ErrorCode.NOT_FOUND));

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    @DisplayName("mapHttpStatus：SERVICE_UNAVAILABLE 码映射 HTTP 503")
    void direct_shouldMapServiceUnavailableCodeTo503() {
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleBusiness(new BusinessException(ErrorCode.SERVICE_UNAVAILABLE));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
    }

    @Test
    @DisplayName("mapHttpStatus：40001 用户域错误码映射 HTTP 400")
    void direct_shouldMapUserDomainCodeTo400() {
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleBusiness(new BusinessException(ErrorCode.USERNAME_EXISTS));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals(ErrorCode.USERNAME_EXISTS.getCode(), response.getBody().getCode());
    }

    @Test
    @DisplayName("mapHttpStatus：30001 文件域错误码映射 HTTP 400")
    void direct_shouldMapFileDomainCodeTo400() {
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleBusiness(new BusinessException(ErrorCode.FILE_TOO_LARGE));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    @DisplayName("mapHttpStatus：20001 AI 域错误码映射 HTTP 503")
    void direct_shouldMapAiDomainCodeTo503() {
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleBusiness(new BusinessException(ErrorCode.AI_API_TIMEOUT));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
    }

    @Test
    @DisplayName("mapHttpStatus：10001 业务域错误码映射 HTTP 400")
    void direct_shouldMapBusinessDomainCodeTo400() {
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleBusiness(new BusinessException(ErrorCode.PROJECT_NOT_FOUND));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    @DisplayName("mapHttpStatus：未知低位错误码（<10000）落回 HTTP 200")
    void direct_shouldMapUnknownLowCodeTo200() {
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleBusiness(new BusinessException(9999, "低频错误码"));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(9999, response.getBody().getCode());
    }

    @Test
    @DisplayName("业务异常响应写入 MDC 中的 traceId")
    void direct_shouldAttachTraceIdFromMdc() {
        MDC.put("traceId", "trace-abc-123");

        ResponseEntity<ApiResponse<Void>> response =
                handler.handleBusiness(new BusinessException(ErrorCode.LOGIN_FAILED));

        assertEquals("trace-abc-123", response.getBody().getTraceId());
    }

    @Test
    @DisplayName("MDC 无 traceId 时响应 traceId 为 null")
    void direct_shouldLeaveTraceIdNullWithoutMdc() {
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleBusiness(new BusinessException(ErrorCode.LOGIN_FAILED));

        assertNull(response.getBody().getTraceId());
    }

    @Test
    @DisplayName("降级模式的 AiException 返回 HTTP 200 但保留 AI 错误码")
    void direct_shouldHandleDegradedAiExceptionAs200() {
        ResponseEntity<ApiResponse<Void>> response = handler.handleAiException(
                new AiException(ErrorCode.AI_MODEL_NOT_FOUND, "模型不可用，已降级", true));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(ErrorCode.AI_MODEL_NOT_FOUND.getCode(), response.getBody().getCode());
        assertEquals("模型不可用，已降级", response.getBody().getMessage());
    }

    @Test
    @DisplayName("非降级的 AiException 按错误码映射 HTTP 503")
    void direct_shouldHandleNonDegradedAiExceptionByMapping() {
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleAiException(new AiException(ErrorCode.AI_API_TIMEOUT));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertEquals(ErrorCode.AI_API_TIMEOUT.getCode(), response.getBody().getCode());
    }

    @Test
    @DisplayName("SSE 断连（IOException）静默吞掉不产生响应体")
    void direct_shouldSwallowIoException() {
        assertDoesNotThrow(() -> handler.handleIoException(new IOException("broken pipe")));
    }

    @Test
    @DisplayName("SSE 断连（AsyncRequestNotUsableException）静默吞掉不产生响应体")
    void direct_shouldSwallowAsyncRequestNotUsableException() {
        assertDoesNotThrow(() ->
                handler.handleIoException(new AsyncRequestNotUsableException("async not usable")));
    }

    @Test
    @DisplayName("静态资源缺失映射为 HTTP 404 + 资源不存在")
    void direct_shouldHandleNoResourceFoundAs404() {
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleNoResource(new NoResourceFoundException(HttpMethod.GET, "/missing.js"));

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals(ErrorCode.NOT_FOUND.getCode(), response.getBody().getCode());
        assertEquals("资源不存在", response.getBody().getMessage());
    }

    @Test
    @DisplayName("未知异常兜底为 HTTP 500 + INTERNAL_ERROR")
    void direct_shouldHandleUnknownExceptionAs500() {
        ResponseEntity<ApiResponse<Void>> response = handler.handleUnknown(new RuntimeException("boom"));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertEquals(ErrorCode.INTERNAL_ERROR.getCode(), response.getBody().getCode());
        assertEquals(ErrorCode.INTERNAL_ERROR.getMessage(), response.getBody().getMessage());
    }

    @Test
    @DisplayName("参数校验无字段错误明细时兜底文案为『参数校验失败』")
    void direct_shouldFallbackValidationMessageWhenNoFieldErrors() throws Exception {
        MethodParameter methodParameter = new MethodParameter(
                ClientErrorProbeController.class.getMethod("typeMismatch", Long.class), 0);
        BeanPropertyBindingResult bindingResult =
                new BeanPropertyBindingResult(new Object(), "probe");
        MethodArgumentNotValidException exception =
                new MethodArgumentNotValidException(methodParameter, bindingResult);

        ResponseEntity<ApiResponse<Void>> response = handler.handleValidation(exception);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("参数校验失败", response.getBody().getMessage());
    }

    // ==================== 边界码与校验明细精确性补强 ====================

    @Test
    @DisplayName("参数校验多个字段错误：明细按『字段: 消息』以『; 』连接")
    void direct_shouldJoinFieldErrorsInOrder() throws Exception {
        MethodParameter methodParameter = new MethodParameter(
                ClientErrorProbeController.class.getMethod("typeMismatch", Long.class), 0);
        BeanPropertyBindingResult bindingResult =
                new BeanPropertyBindingResult(new Object(), "probe");
        bindingResult.addError(new FieldError("probe", "username", "不能为空"));
        bindingResult.addError(new FieldError("probe", "password", "长度不足"));
        MethodArgumentNotValidException exception =
                new MethodArgumentNotValidException(methodParameter, bindingResult);

        ResponseEntity<ApiResponse<Void>> response = handler.handleValidation(exception);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("username: 不能为空; password: 长度不足", response.getBody().getMessage());
    }

    @Test
    @DisplayName("mapHttpStatus：恰 30000 码（30000 段下界）映射 HTTP 400")
    void direct_shouldMapExactlyThirtyThousandTo400() {
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleBusiness(new BusinessException(30000, "边界码"));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals(30000, response.getBody().getCode());
    }

    @Test
    @DisplayName("mapHttpStatus：恰 20000 码（20000 段下界）映射 HTTP 503")
    void direct_shouldMapExactlyTwentyThousandTo503() {
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleBusiness(new BusinessException(20000, "边界码"));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
    }

    @Test
    @DisplayName("mapHttpStatus：恰 10000 码（10000 段下界）映射 HTTP 400")
    void direct_shouldMapExactlyTenThousandTo400() {
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleBusiness(new BusinessException(10000, "边界码"));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }
}
