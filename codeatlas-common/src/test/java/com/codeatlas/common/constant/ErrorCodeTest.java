package com.codeatlas.common.constant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ErrorCode — 统一错误码枚举")
class ErrorCodeTest {

    @Test
    @DisplayName("通用错误码取值精确匹配")
    void generalCodes_shouldMatchExactValues() {
        assertEquals(200, ErrorCode.SUCCESS.getCode());
        assertEquals("成功", ErrorCode.SUCCESS.getMessage());
        assertEquals(400, ErrorCode.BAD_REQUEST.getCode());
        assertEquals("请求参数错误", ErrorCode.BAD_REQUEST.getMessage());
        assertEquals(401, ErrorCode.UNAUTHORIZED.getCode());
        assertEquals("未登录或 Token 已过期", ErrorCode.UNAUTHORIZED.getMessage());
        assertEquals(403, ErrorCode.FORBIDDEN.getCode());
        assertEquals("无权限访问该资源", ErrorCode.FORBIDDEN.getMessage());
        assertEquals(404, ErrorCode.NOT_FOUND.getCode());
        assertEquals("资源不存在", ErrorCode.NOT_FOUND.getMessage());
        assertEquals(409, ErrorCode.CONFLICT.getCode());
        assertEquals("资源冲突", ErrorCode.CONFLICT.getMessage());
        assertEquals(500, ErrorCode.INTERNAL_ERROR.getCode());
        assertEquals("服务器内部错误", ErrorCode.INTERNAL_ERROR.getMessage());
        assertEquals(503, ErrorCode.SERVICE_UNAVAILABLE.getCode());
        assertEquals("服务暂时不可用", ErrorCode.SERVICE_UNAVAILABLE.getMessage());
    }

    @Test
    @DisplayName("业务错误码取值精确匹配")
    void businessCodes_shouldMatchExactValues() {
        assertEquals(10001, ErrorCode.PROJECT_NOT_FOUND.getCode());
        assertEquals("项目不存在", ErrorCode.PROJECT_NOT_FOUND.getMessage());
        assertEquals(10005, ErrorCode.SOURCE_CLONE_FAILED.getCode());
        assertEquals("源码仓库克隆失败", ErrorCode.SOURCE_CLONE_FAILED.getMessage());
        assertEquals(10011, ErrorCode.REPORT_GENERATE_FAILED.getCode());
        assertEquals("报告生成失败", ErrorCode.REPORT_GENERATE_FAILED.getMessage());
    }

    @Test
    @DisplayName("AI 错误码取值精确匹配")
    void aiCodes_shouldMatchExactValues() {
        assertEquals(20001, ErrorCode.AI_API_TIMEOUT.getCode());
        assertEquals("AI API 调用超时", ErrorCode.AI_API_TIMEOUT.getMessage());
        assertEquals(20003, ErrorCode.AI_API_AUTH_FAILED.getCode());
        assertEquals("AI API 认证失败", ErrorCode.AI_API_AUTH_FAILED.getMessage());
        assertEquals(20010, ErrorCode.AI_CONTEXT_TOO_LARGE.getCode());
        assertEquals("项目过大，超出模型上下文限制", ErrorCode.AI_CONTEXT_TOO_LARGE.getMessage());
    }

    @Test
    @DisplayName("文件与用户错误码取值精确匹配")
    void fileAndUserCodes_shouldMatchExactValues() {
        assertEquals(30001, ErrorCode.FILE_TOO_LARGE.getCode());
        assertEquals("文件大小超过限制", ErrorCode.FILE_TOO_LARGE.getMessage());
        assertEquals(40001, ErrorCode.USERNAME_EXISTS.getCode());
        assertEquals("用户名已存在", ErrorCode.USERNAME_EXISTS.getMessage());
        assertEquals(40004, ErrorCode.USER_DISABLED.getCode());
        assertEquals("账户已被禁用", ErrorCode.USER_DISABLED.getMessage());
        assertEquals(40005, ErrorCode.LOGIN_FAILED.getCode());
        assertEquals("用户名或密码错误", ErrorCode.LOGIN_FAILED.getMessage());
    }

    @Test
    @DisplayName("所有枚举值 code 非零、message 非空白且 code 全局唯一")
    void allValues_shouldHaveNonNullMessageAndUniqueCodes() {
        Set<Integer> codes = new HashSet<>();
        for (ErrorCode value : ErrorCode.values()) {
            assertFalse(value.getMessage() == null || value.getMessage().trim().isEmpty(),
                    value.name() + " 的 message 不应为空白");
            assertTrue(codes.add(value.getCode()), "code 重复: " + value.getCode());
            assertNotNull(value.getCode());
        }
        assertEquals(37, ErrorCode.values().length, "错误码总数变化需显式确认");
    }
}
