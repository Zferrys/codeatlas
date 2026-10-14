package com.codeatlas.common.dto;

import com.codeatlas.common.constant.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ApiResponse — 统一 API 响应包装")
class ApiResponseTest {

    @Test
    @DisplayName("success(data)：code=200、message=success、data 原样持有、timestamp 已填充")
    void success_withData_shouldWrapData() {
        List<String> payload = Arrays.asList("a", "b");

        ApiResponse<List<String>> response = ApiResponse.success(payload);

        assertEquals(200, response.getCode());
        assertEquals("success", response.getMessage());
        assertSame(payload, response.getData());
        assertTrue(response.getTimestamp() > 0, "timestamp 应在构造时填充");
        assertEquals(MDC.get("traceId"), response.getTraceId(), "traceId 应取自 MDC 当前上下文");
    }

    @Test
    @DisplayName("success()：等价于 success(null)，data 为 null")
    void success_withoutData_shouldHaveNullData() {
        ApiResponse<Object> response = ApiResponse.success();

        assertEquals(200, response.getCode());
        assertEquals("success", response.getMessage());
        assertNull(response.getData());
    }

    @Test
    @DisplayName("error(code, message)：原样写入自定义 code 与 message，data 保持 null")
    void error_withRawCodeAndMessage_shouldKeepBoth() {
        ApiResponse<String> response = ApiResponse.error(422, "参数校验失败");

        assertEquals(422, response.getCode());
        assertEquals("参数校验失败", response.getMessage());
        assertNull(response.getData());
    }

    @Test
    @DisplayName("error(ErrorCode)：code 与 message 均取自枚举")
    void error_withErrorCode_shouldUseEnumCodeAndMessage() {
        ApiResponse<Void> response = ApiResponse.error(ErrorCode.PROJECT_NOT_FOUND);

        assertEquals(10001, response.getCode());
        assertEquals("项目不存在", response.getMessage());
    }

    @Test
    @DisplayName("error(ErrorCode, detail)：非空 detail 覆盖枚举描述，code 仍取枚举")
    void error_withDetail_shouldOverrideMessage() {
        ApiResponse<Void> response = ApiResponse.error(ErrorCode.SCAN_NOT_FOUND, "扫描记录 7 不存在");

        assertEquals(10004, response.getCode());
        assertEquals("扫描记录 7 不存在", response.getMessage());
    }

    @Test
    @DisplayName("error(ErrorCode, null)：detail 为 null 时回退到枚举描述")
    void error_withNullDetail_shouldFallbackToEnumMessage() {
        ApiResponse<Void> response = ApiResponse.error(ErrorCode.FORBIDDEN, null);

        assertEquals(403, response.getCode());
        assertEquals("无权限访问该资源", response.getMessage());
    }

    @Test
    @DisplayName("setter 可逐项覆盖（traceId/timestamp/data）")
    void setters_shouldOverrideEachField() {
        ApiResponse<String> response = new ApiResponse<>();
        response.setCode(500);
        response.setMessage("oops");
        response.setData("data");
        response.setTraceId("trace-123");
        response.setTimestamp(1234567890L);

        assertEquals(500, response.getCode());
        assertEquals("oops", response.getMessage());
        assertEquals("data", response.getData());
        assertEquals("trace-123", response.getTraceId());
        assertEquals(1234567890L, response.getTimestamp());
    }
}
