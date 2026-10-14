package com.codeatlas.engine.rule;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("RuleDefinition — 规则定义（引擎层 DTO）")
class RuleDefinitionTest {

    @Test
    @DisplayName("无参构造器：全字段 null（isEnabled 未设置时后续按启用处理）")
    void defaultConstructor_shouldBeAllNull() {
        RuleDefinition rule = new RuleDefinition();

        assertNull(rule.getId());
        assertNull(rule.getName());
        assertNull(rule.getIsEnabled());
        assertNull(rule.getSeverity());
        assertNull(rule.getRuleDefinition());
    }

    @Test
    @DisplayName("全参构造器：五字段原样持有")
    void fullConstructor_shouldStoreAllFields() {
        RuleDefinition rule = new RuleDefinition(7L, "no-star-import", Boolean.TRUE,
                "INFO", "{\"type\":\"forbidden_pattern\"}");

        assertEquals(7L, rule.getId());
        assertEquals("no-star-import", rule.getName());
        assertTrue(rule.getIsEnabled());
        assertEquals("INFO", rule.getSeverity());
        assertEquals("{\"type\":\"forbidden_pattern\"}", rule.getRuleDefinition());
    }

    @Test
    @DisplayName("setter/getter 全字段往返（含 isEnabled=false 与 null 清空）")
    void accessors_shouldRoundTrip() {
        RuleDefinition rule = new RuleDefinition();
        rule.setId(99L);
        rule.setName("max-public-methods");
        rule.setIsEnabled(Boolean.FALSE);
        rule.setSeverity("WARN");
        rule.setRuleDefinition("{\"max\":10}");

        assertEquals(99L, rule.getId());
        assertEquals("max-public-methods", rule.getName());
        assertFalse(rule.getIsEnabled());
        assertEquals("WARN", rule.getSeverity());
        assertEquals("{\"max\":10}", rule.getRuleDefinition());

        rule.setIsEnabled(null);
        assertNull(rule.getIsEnabled());
    }
}
