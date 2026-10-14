package com.codeatlas.server.dto.request;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@DisplayName("RegisterRequest DTO 单元测试")
class RegisterRequestTest {

    @Test
    @DisplayName("未设置字段时 getter 返回 null（邮箱尤其不得为默认空串）")
    void getters_unsetFields_shouldReturnNull() {
        RegisterRequest request = new RegisterRequest();

        assertNull(request.getUsername());
        assertNull(request.getPassword());
        assertNull(request.getEmail());
    }

    @Test
    @DisplayName("setEmail 后 getEmail 原样返回")
    void setEmail_shouldRoundTrip() {
        RegisterRequest request = new RegisterRequest();

        request.setEmail("user@example.com");

        assertEquals("user@example.com", request.getEmail());
    }
}
