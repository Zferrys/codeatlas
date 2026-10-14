package com.codeatlas.server.dto.request;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@DisplayName("ChangePasswordRequest 单元测试（全字段读写）")
class ChangePasswordRequestTest {

    @Test
    @DisplayName("新建实例：oldPassword/newPassword 默认 null")
    void newInstance_shouldHaveAllFieldsNull() {
        ChangePasswordRequest request = new ChangePasswordRequest();

        assertNull(request.getOldPassword());
        assertNull(request.getNewPassword());
    }

    @Test
    @DisplayName("setter/getter：新旧密码原值存取（互不串位）")
    void accessors_shouldRoundTripDistinctValues() {
        ChangePasswordRequest request = new ChangePasswordRequest();

        request.setOldPassword("OldPass123");
        request.setNewPassword("NewPass456!");

        assertEquals("OldPass123", request.getOldPassword());
        assertEquals("NewPass456!", request.getNewPassword());
    }

    @Test
    @DisplayName("setter：传入 null 可将字段回置为空")
    void setters_shouldAllowNullReset() {
        ChangePasswordRequest request = new ChangePasswordRequest();
        request.setOldPassword("a");
        request.setNewPassword("b");

        request.setOldPassword(null);
        request.setNewPassword(null);

        assertNull(request.getOldPassword());
        assertNull(request.getNewPassword());
    }
}
