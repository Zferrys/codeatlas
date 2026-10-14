package com.codeatlas.server.service;

import com.codeatlas.engine.ai.ToolCall;
import com.codeatlas.engine.ai.ToolDef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ToolRegistry 单元测试")
class ToolRegistryTest {

    private static final Long PROJECT_ID = 42L;

    /** 可配置的假工具：记录最近一次执行入参，可指定返回值或抛出异常 */
    private static class FakeTool implements Tool {

        private final String name;
        private final String description;
        private final Map<String, Object> schema;
        private final String result;
        private final RuntimeException failure;

        private Map<String, Object> lastArguments;
        private Long lastProjectId;

        FakeTool(String name, String description, Map<String, Object> schema, String result, RuntimeException failure) {
            this.name = name;
            this.description = description;
            this.schema = schema;
            this.result = result;
            this.failure = failure;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getDescription() {
            return description;
        }

        @Override
        public Map<String, Object> getParametersSchema() {
            return schema;
        }

        @Override
        public String execute(Map<String, Object> arguments, Long projectId) {
            this.lastArguments = arguments;
            this.lastProjectId = projectId;
            if (failure != null) {
                throw failure;
            }
            return result;
        }
    }

    @Test
    @DisplayName("构造注册：按注册顺序输出全部 ToolDef，字段完整")
    void getToolDefs_shouldReturnDefsInRegistrationOrder() {
        Map<String, Object> schemaA = Map.of("type", "object");
        Map<String, Object> schemaB = Map.of("type", "object", "properties", Map.of());
        ToolRegistry registry = new ToolRegistry(List.of(
                new FakeTool("alpha", "工具A", schemaA, "ok", null),
                new FakeTool("beta", "工具B", schemaB, "ok", null)));

        List<ToolDef> defs = registry.getToolDefs();

        assertEquals(2, defs.size());
        assertEquals("alpha", defs.get(0).getName());
        assertEquals("工具A", defs.get(0).getDescription());
        assertSame(schemaA, defs.get(0).getParameters());
        assertEquals("beta", defs.get(1).getName());
        assertEquals("工具B", defs.get(1).getDescription());
        assertSame(schemaB, defs.get(1).getParameters());
    }

    @Test
    @DisplayName("空注册表：返回空 ToolDef 列表")
    void getToolDefs_emptyRegistry_shouldReturnEmptyList() {
        ToolRegistry registry = new ToolRegistry(List.of());

        assertTrue(registry.getToolDefs().isEmpty());
    }

    @Test
    @DisplayName("执行未知工具：返回错误串，不抛异常")
    void execute_unknownTool_shouldReturnErrorText() {
        ToolRegistry registry = new ToolRegistry(List.of(
                new FakeTool("alpha", "工具A", Map.of(), "ok", null)));
        ToolCall call = new ToolCall("call-1", "missing", Map.of());

        String result = registry.execute(call, PROJECT_ID);

        assertEquals("(错误) 未知工具: missing", result);
    }

    @Test
    @DisplayName("执行已知工具：原样透传参数与项目 ID 并返回结果")
    void execute_knownTool_shouldDelegateArgumentsAndProjectId() {
        FakeTool tool = new FakeTool("alpha", "工具A", Map.of(), "执行结果", null);
        ToolRegistry registry = new ToolRegistry(List.of(tool));
        Map<String, Object> arguments = Map.of("query", "Service");
        ToolCall call = new ToolCall("call-1", "alpha", arguments);

        String result = registry.execute(call, PROJECT_ID);

        assertEquals("执行结果", result);
        assertSame(arguments, tool.lastArguments);
        assertEquals(PROJECT_ID, tool.lastProjectId);
    }

    @Test
    @DisplayName("执行抛异常：捕获并返回带异常消息的错误串")
    void execute_toolThrows_shouldReturnErrorWithMessage() {
        ToolRegistry registry = new ToolRegistry(List.of(
                new FakeTool("alpha", "工具A", Map.of(), null, new IllegalStateException("boom"))));
        ToolCall call = new ToolCall("call-1", "alpha", Map.of());

        String result = registry.execute(call, PROJECT_ID);

        assertEquals("(错误) 工具执行失败: boom", result);
    }

    @Test
    @DisplayName("执行抛异常且消息为 null：错误串拼接 null 不抛二次异常")
    void execute_toolThrowsWithoutMessage_shouldReturnNullMessageText() {
        ToolRegistry registry = new ToolRegistry(List.of(
                new FakeTool("alpha", "工具A", Map.of(), null, new IllegalStateException())));
        ToolCall call = new ToolCall("call-1", "alpha", null);

        String result = registry.execute(call, PROJECT_ID);

        assertEquals("(错误) 工具执行失败: null", result);
    }
}
