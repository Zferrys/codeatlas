package com.codeatlas.engine.rule;

/**
 * 规则定义 — 引擎层 DTO，不依赖 Spring 或 server 模块。
 */
public class RuleDefinition {
    private Long id;
    private String name;
    private Boolean isEnabled;
    private String severity;
    private String ruleDefinition;

    public RuleDefinition() {}

    public RuleDefinition(Long id, String name, Boolean isEnabled, String severity, String ruleDefinition) {
        this.id = id;
        this.name = name;
        this.isEnabled = isEnabled;
        this.severity = severity;
        this.ruleDefinition = ruleDefinition;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Boolean getIsEnabled() { return isEnabled; }
    public void setIsEnabled(Boolean isEnabled) { this.isEnabled = isEnabled; }
    public String getSeverity() { return severity; }
    public void setSeverity(String severity) { this.severity = severity; }
    public String getRuleDefinition() { return ruleDefinition; }
    public void setRuleDefinition(String ruleDefinition) { this.ruleDefinition = ruleDefinition; }
}
