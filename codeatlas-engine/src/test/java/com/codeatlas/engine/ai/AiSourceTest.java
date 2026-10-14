package com.codeatlas.engine.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@DisplayName("AiSource — 源码引用（去幻觉定位）")
class AiSourceTest {

    @Test
    @DisplayName("无参构造器：lineNumber 默认 -1 表示无行号，其余字段为 null")
    void defaultConstructor_shouldSetLineNumberMinusOne() {
        AiSource source = new AiSource();

        assertEquals(-1, source.getLineNumber());
        assertNull(source.getClassName());
        assertNull(source.getMethodName());
        assertNull(source.getSnippet());
    }

    @Test
    @DisplayName("类名构造器：className 填充，lineNumber 仍为 -1")
    void classNameConstructor_shouldSetClassNameAndDefaultLineNumber() {
        AiSource source = new AiSource("com.codeatlas.engine.ai.AiSource");

        assertEquals("com.codeatlas.engine.ai.AiSource", source.getClassName());
        assertEquals(-1, source.getLineNumber());
        assertNull(source.getMethodName());
        assertNull(source.getSnippet());
    }

    @Test
    @DisplayName("全字段 setter/getter 原样往返（含显式覆盖 lineNumber=-1）")
    void accessors_shouldRoundTrip() {
        AiSource source = new AiSource();
        source.setClassName("com.demo.Service");
        source.setMethodName("handle");
        source.setLineNumber(42);
        source.setSnippet("return result;");

        assertEquals("com.demo.Service", source.getClassName());
        assertEquals("handle", source.getMethodName());
        assertEquals(42, source.getLineNumber());
        assertEquals("return result;", source.getSnippet());

        source.setLineNumber(-1);
        assertEquals(-1, source.getLineNumber());
    }
}
