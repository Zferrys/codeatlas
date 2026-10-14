package com.codeatlas.engine.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

@DisplayName("ToolDef — 工具定义（函数 schema）")
class ToolDefTest {

    @Test
    @DisplayName("无参构造器：全字段 null")
    void defaultConstructor_shouldBeAllNull() {
        ToolDef tool = new ToolDef();

        assertNull(tool.getName());
        assertNull(tool.getDescription());
        assertNull(tool.getParameters());
    }

    @Test
    @DisplayName("全参构造器：三字段原样持有")
    void fullConstructor_shouldStoreAllFields() {
        Map<String, Object> parameters = Collections.singletonMap("type", "object");

        ToolDef tool = new ToolDef("search_classes", "按关键词搜索类", parameters);

        assertEquals("search_classes", tool.getName());
        assertEquals("按关键词搜索类", tool.getDescription());
        assertSame(parameters, tool.getParameters());
    }

    @Test
    @DisplayName("setter/getter 原样往返")
    void accessors_shouldRoundTrip() {
        ToolDef tool = new ToolDef();
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("type", "object");
        tool.setName("get_dependencies");
        tool.setDescription("查询依赖");
        tool.setParameters(parameters);

        assertEquals("get_dependencies", tool.getName());
        assertEquals("查询依赖", tool.getDescription());
        assertSame(parameters, tool.getParameters());
    }

    @Test
    @DisplayName("setParameters(null) 允许显式清空（由序列化端自行兜底）")
    void setParameters_null_shouldBeAllowed() {
        ToolDef tool = new ToolDef("t", "d", Collections.emptyMap());

        tool.setParameters(null);

        assertNull(tool.getParameters());
    }
}
