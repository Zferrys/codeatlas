package com.codeatlas.common.exception;

import com.codeatlas.common.constant.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("BusinessException — 统一业务异常")
class BusinessExceptionTest {

    @Test
    @DisplayName("仅传错误码时：code 取自枚举、message 取自枚举描述")
    void constructor_withErrorCodeOnly_shouldUseEnumMessage() {
        BusinessException ex = new BusinessException(ErrorCode.PROJECT_NOT_FOUND);

        assertEquals(10001, ex.getCode());
        assertEquals("项目不存在", ex.getMessage());
        assertNull(ex.getCause());
    }

    @Test
    @DisplayName("错误码 + 非空 detail：message 使用 detail 覆盖枚举描述")
    void constructor_withNonBlankDetail_shouldOverrideMessage() {
        BusinessException ex = new BusinessException(ErrorCode.PROJECT_ACCESS_DENIED, "项目 42 无权访问");

        assertEquals(10002, ex.getCode());
        assertEquals("项目 42 无权访问", ex.getMessage());
    }

    @Test
    @DisplayName("错误码 + null detail：回退到枚举描述")
    void constructor_withNullDetail_shouldFallbackToEnumMessage() {
        BusinessException ex = new BusinessException(ErrorCode.SCAN_NOT_FOUND, null);

        assertEquals(10004, ex.getCode());
        assertEquals("扫描记录不存在", ex.getMessage());
    }

    @Test
    @DisplayName("错误码 + 空串 detail：不做回退，保留空串（与 null 语义不同）")
    void constructor_withEmptyDetail_shouldKeepEmptyString() {
        BusinessException ex = new BusinessException(ErrorCode.SCAN_NOT_FOUND, "");

        assertEquals(10004, ex.getCode());
        assertEquals("", ex.getMessage());
    }

    @Test
    @DisplayName("自定义 code + message：原样保留")
    void constructor_withRawCodeAndMessage_shouldKeepBoth() {
        BusinessException ex = new BusinessException(99001, "自定义业务错误");

        assertEquals(99001, ex.getCode());
        assertEquals("自定义业务错误", ex.getMessage());
    }

    @Test
    @DisplayName("是 RuntimeException 子类，可被捕获")
    void isRuntimeException() {
        RuntimeException caught = assertThrows(RuntimeException.class, () -> {
            throw new BusinessException(ErrorCode.PROJECT_NOT_FOUND);
        });
        assertEquals("项目不存在", caught.getMessage());
    }
}
