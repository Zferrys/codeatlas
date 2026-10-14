package com.codeatlas.engine.rule;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@DisplayName("ViolationResult — 违规结果（引擎层 DTO）")
class ViolationResultTest {

    @Test
    @DisplayName("无参构造器：全字段 null")
    void defaultConstructor_shouldBeAllNull() {
        ViolationResult result = new ViolationResult();

        assertNull(result.getRuleId());
        assertNull(result.getSeverity());
        assertNull(result.getClassFqn());
        assertNull(result.getMessage());
        assertNull(result.getSuggestion());
    }

    @Test
    @DisplayName("全参构造器：五字段原样持有")
    void fullConstructor_shouldStoreAllFields() {
        ViolationResult result = new ViolationResult(3L, "BLOCKER",
                "com.demo.controller.UserController",
                "Controller 直接依赖 DAO/Repository: UserRepository",
                "通过 Service 层封装调用");

        assertEquals(3L, result.getRuleId());
        assertEquals("BLOCKER", result.getSeverity());
        assertEquals("com.demo.controller.UserController", result.getClassFqn());
        assertEquals("Controller 直接依赖 DAO/Repository: UserRepository", result.getMessage());
        assertEquals("通过 Service 层封装调用", result.getSuggestion());
    }

    @Test
    @DisplayName("setter/getter 全字段往返")
    void accessors_shouldRoundTrip() {
        ViolationResult result = new ViolationResult();
        result.setRuleId(11L);
        result.setSeverity("WARN");
        result.setClassFqn("com.demo.SomeService");
        result.setMessage("公开方法数 25 超过上限 20");
        result.setSuggestion("考虑拆分类");

        assertEquals(11L, result.getRuleId());
        assertEquals("WARN", result.getSeverity());
        assertEquals("com.demo.SomeService", result.getClassFqn());
        assertEquals("公开方法数 25 超过上限 20", result.getMessage());
        assertEquals("考虑拆分类", result.getSuggestion());
    }
}
