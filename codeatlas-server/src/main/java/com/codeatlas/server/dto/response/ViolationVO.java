package com.codeatlas.server.dto.response;

import com.codeatlas.server.entity.ViolationEntity;

import java.time.LocalDateTime;

public class ViolationVO {

    private Long id;
    private Long scanId;
    private Long ruleId;
    private Long projectId;
    private String severity;
    private String classFqn;
    private String methodName;
    private Integer lineNumber;
    private String message;
    private String suggestion;
    private Boolean isResolved;
    private LocalDateTime resolvedAt;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getScanId() { return scanId; }
    public void setScanId(Long scanId) { this.scanId = scanId; }
    public Long getRuleId() { return ruleId; }
    public void setRuleId(Long ruleId) { this.ruleId = ruleId; }
    public Long getProjectId() { return projectId; }
    public void setProjectId(Long projectId) { this.projectId = projectId; }
    public String getSeverity() { return severity; }
    public void setSeverity(String severity) { this.severity = severity; }
    public String getClassFqn() { return classFqn; }
    public void setClassFqn(String classFqn) { this.classFqn = classFqn; }
    public String getMethodName() { return methodName; }
    public void setMethodName(String methodName) { this.methodName = methodName; }
    public Integer getLineNumber() { return lineNumber; }
    public void setLineNumber(Integer lineNumber) { this.lineNumber = lineNumber; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public String getSuggestion() { return suggestion; }
    public void setSuggestion(String suggestion) { this.suggestion = suggestion; }
    public Boolean getIsResolved() { return isResolved; }
    public void setIsResolved(Boolean isResolved) { this.isResolved = isResolved; }
    public LocalDateTime getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(LocalDateTime resolvedAt) { this.resolvedAt = resolvedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public static ViolationVO from(ViolationEntity entity) {
        ViolationVO vo = new ViolationVO();
        vo.setId(entity.getId());
        vo.setScanId(entity.getScanId());
        vo.setRuleId(entity.getRuleId());
        vo.setProjectId(entity.getProjectId());
        vo.setSeverity(entity.getSeverity());
        vo.setClassFqn(entity.getClassFqn());
        vo.setMethodName(entity.getMethodName());
        vo.setLineNumber(entity.getLineNumber());
        vo.setMessage(entity.getMessage());
        vo.setSuggestion(entity.getSuggestion());
        vo.setIsResolved(entity.getIsResolved());
        vo.setResolvedAt(entity.getResolvedAt());
        vo.setCreatedAt(entity.getCreatedAt());
        return vo;
    }
}
