package com.codeatlas.server.dto.response;

import com.codeatlas.server.entity.ConstitutionRuleEntity;

import java.time.LocalDateTime;

public class ConstitutionRuleVO {

    private Long id;
    private Long projectId;
    private String name;
    private String description;
    private String category;
    private String severity;
    private String ruleDefinition;
    private Boolean isEnabled;
    private Integer version;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getProjectId() { return projectId; }
    public void setProjectId(Long projectId) { this.projectId = projectId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getSeverity() { return severity; }
    public void setSeverity(String severity) { this.severity = severity; }
    public String getRuleDefinition() { return ruleDefinition; }
    public void setRuleDefinition(String ruleDefinition) { this.ruleDefinition = ruleDefinition; }
    public Boolean getIsEnabled() { return isEnabled; }
    public void setIsEnabled(Boolean isEnabled) { this.isEnabled = isEnabled; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public static ConstitutionRuleVO from(ConstitutionRuleEntity entity) {
        ConstitutionRuleVO vo = new ConstitutionRuleVO();
        vo.setId(entity.getId());
        vo.setProjectId(entity.getProjectId());
        vo.setName(entity.getName());
        vo.setDescription(entity.getDescription());
        vo.setCategory(entity.getCategory());
        vo.setSeverity(entity.getSeverity());
        vo.setRuleDefinition(entity.getRuleDefinition());
        vo.setIsEnabled(entity.getIsEnabled());
        vo.setVersion(entity.getVersion());
        vo.setCreatedAt(entity.getCreatedAt());
        return vo;
    }
}
