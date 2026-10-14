package com.codeatlas.engine.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

@DisplayName("ToolCall — 工具调用（参数安全读取）")
class ToolCallTest {

    @Test
    @DisplayName("无参构造器：全字段 null")
    void defaultConstructor_shouldBeAllNull() {
        ToolCall call = new ToolCall();

        assertNull(call.getId());
        assertNull(call.getName());
        assertNull(call.getArguments());
    }

    @Test
    @DisplayName("全参构造器：三字段原样持有")
    void fullConstructor_shouldStoreAllFields() {
        Map<String, Object> arguments = Collections.singletonMap("keyword", "demo");

        ToolCall call = new ToolCall("call_1", "search_classes", arguments);

        assertEquals("call_1", call.getId());
        assertEquals("search_classes", call.getName());
        assertSame(arguments, call.getArguments());
    }

    @Test
    @DisplayName("setter/getter 原样往返")
    void accessors_shouldRoundTrip() {
        ToolCall call = new ToolCall();
        Map<String, Object> arguments = new HashMap<>();
        call.setId("id2");
        call.setName("get_detail");
        call.setArguments(arguments);

        assertEquals("id2", call.getId());
        assertEquals("get_detail", call.getName());
        assertSame(arguments, call.getArguments());
    }

    @Test
    @DisplayName("getStringArg：arguments 为 null 时返回 null")
    void getStringArg_nullArguments_shouldReturnNull() {
        ToolCall call = new ToolCall();

        assertNull(call.getStringArg("keyword"));
    }

    @Test
    @DisplayName("getStringArg：键不存在时返回 null")
    void getStringArg_missingKey_shouldReturnNull() {
        ToolCall call = new ToolCall();
        call.setArguments(Collections.singletonMap("other", "x"));

        assertNull(call.getStringArg("keyword"));
    }

    @Test
    @DisplayName("getStringArg：键存在时返回 toString 结果（字符串原样）")
    void getStringArg_existingStringKey_shouldReturnValue() {
        ToolCall call = new ToolCall();
        call.setArguments(Collections.singletonMap("keyword", "UserService"));

        assertEquals("UserService", call.getStringArg("keyword"));
    }

    @Test
    @DisplayName("getStringArg：非字符串值转为字符串（Integer 42 → \"42\"）")
    void getStringArg_nonStringValue_shouldBeConverted() {
        ToolCall call = new ToolCall();
        Map<String, Object> arguments = new HashMap<>();
        arguments.put("limit", 42);
        arguments.put("flag", true);
        call.setArguments(arguments);

        assertEquals("42", call.getStringArg("limit"));
        assertEquals("true", call.getStringArg("flag"));
    }

    @Test
    @DisplayName("getStringArg：键存在但值为 null 时返回 null")
    void getStringArg_nullValue_shouldReturnNull() {
        ToolCall call = new ToolCall();
        Map<String, Object> arguments = new HashMap<>();
        arguments.put("keyword", null);
        call.setArguments(arguments);

        assertNull(call.getStringArg("keyword"));
    }
}
