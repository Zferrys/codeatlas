package com.codeatlas.common.exception;

import com.codeatlas.common.constant.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("AiException — AI 服务异常（含降级标记）")
class AiExceptionTest {

    @Test
    @DisplayName("仅传错误码：code/message 取自枚举，degraded 默认 false")
    void constructor_withErrorCodeOnly_shouldUseEnumAndNotDegraded() {
        AiException ex = new AiException(ErrorCode.AI_API_TIMEOUT);

        assertEquals(20001, ex.getCode());
        assertEquals("AI API 调用超时", ex.getMessage());
        assertFalse(ex.isDegraded());
    }

    @Test
    @DisplayName("错误码 + 非空 detail：message 使用 detail，degraded 仍为 false")
    void constructor_withDetail_shouldOverrideMessage() {
        AiException ex = new AiException(ErrorCode.AI_API_RATE_LIMITED, "请求过于频繁，请稍后重试");

        assertEquals(20002, ex.getCode());
        assertEquals("请求过于频繁，请稍后重试", ex.getMessage());
        assertFalse(ex.isDegraded());
    }

    @Test
    @DisplayName("错误码 + null detail：回退到枚举描述")
    void constructor_withNullDetail_shouldFallbackToEnumMessage() {
        AiException ex = new AiException(ErrorCode.AI_API_AUTH_FAILED, null);

        assertEquals(20003, ex.getCode());
        assertEquals("AI API 认证失败", ex.getMessage());
    }

    @Test
    @DisplayName("三参构造器：degraded=true 精确置位（降级模式）")
    void constructor_withDegradedTrue_shouldFlagDegraded() {
        AiException ex = new AiException(ErrorCode.AI_API_INTERNAL_ERROR, "主模型不可用，已切换备用模型", true);

        assertEquals(20004, ex.getCode());
        assertEquals("主模型不可用，已切换备用模型", ex.getMessage());
        assertTrue(ex.isDegraded());
    }

    @Test
    @DisplayName("三参构造器：degraded=false 时不误置降级标记")
    void constructor_withDegradedFalse_shouldNotFlagDegraded() {
        AiException ex = new AiException(ErrorCode.AI_HALLUCINATION_DETECTED, "引用不存在", false);

        assertEquals(20008, ex.getCode());
        assertFalse(ex.isDegraded());
    }

    @Test
    @DisplayName("三参构造器 + null detail：回退到枚举描述且保持降级标记")
    void constructor_withNullDetailAndDegraded_shouldFallbackMessageKeepFlag() {
        AiException ex = new AiException(ErrorCode.AI_TOKEN_BUDGET_EXCEEDED, null, true);

        assertEquals(20009, ex.getCode());
        assertEquals("Token 月度预算已耗尽", ex.getMessage());
        assertTrue(ex.isDegraded());
    }

    @Test
    @DisplayName("是 RuntimeException 子类，可被捕获（前端按 code 降级提示）")
    void isRuntimeException() {
        RuntimeException caught = assertThrows(RuntimeException.class, () -> {
            throw new AiException(ErrorCode.AI_MODEL_NOT_FOUND);
        });
        assertEquals("模型不存在或未配置", caught.getMessage());
    }
}
