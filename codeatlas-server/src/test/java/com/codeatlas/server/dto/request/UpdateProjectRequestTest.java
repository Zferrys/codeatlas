package com.codeatlas.server.dto.request;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@DisplayName("UpdateProjectRequest 单元测试（全字段读写）")
class UpdateProjectRequestTest {

    @Test
    @DisplayName("新建实例：name/description 默认 null")
    void newInstance_shouldHaveAllFieldsNull() {
        UpdateProjectRequest request = new UpdateProjectRequest();

        assertNull(request.getName());
        assertNull(request.getDescription());
    }

    @Test
    @DisplayName("setter/getter：name 与 description 原值存取（互不串位）")
    void accessors_shouldRoundTripDistinctValues() {
        UpdateProjectRequest request = new UpdateProjectRequest();

        request.setName("CodeAtlas 重构版");
        request.setDescription("增加全量单元测试与变异测试覆盖");

        assertEquals("CodeAtlas 重构版", request.getName());
        assertEquals("增加全量单元测试与变异测试覆盖", request.getDescription());
    }

    @Test
    @DisplayName("setter：传入 null 可将字段回置为空")
    void setters_shouldAllowNullReset() {
        UpdateProjectRequest request = new UpdateProjectRequest();
        request.setName("x");
        request.setDescription("y");

        request.setName(null);
        request.setDescription(null);

        assertNull(request.getName());
        assertNull(request.getDescription());
    }
}
