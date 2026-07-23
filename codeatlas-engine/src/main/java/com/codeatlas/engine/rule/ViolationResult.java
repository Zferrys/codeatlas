package com.codeatlas.engine.rule;

/**
 * 违规结果 — 引擎层 DTO，不依赖 Spring 或 server 模块。
 */
public class ViolationResult {
    private Long ruleId;
    private String severity;
    private String classFqn;
    private String message;
    private String suggestion;

    public ViolationResult() {}

    public ViolationResult(Long ruleId, String severity, String classFqn, String message, String suggestion) {
        this.ruleId = ruleId;
        this.severity = severity;
        this.classFqn = classFqn;
        this.message = message;
        this.suggestion = suggestion;
    }

    public Long getRuleId() { return ruleId; }
    public void setRuleId(Long ruleId) { this.ruleId = ruleId; }
    public String getSeverity() { return severity; }
    public void setSeverity(String severity) { this.severity = severity; }
    public String getClassFqn() { return classFqn; }
    public void setClassFqn(String classFqn) { this.classFqn = classFqn; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public String getSuggestion() { return suggestion; }
    public void setSuggestion(String suggestion) { this.suggestion = suggestion; }
}
