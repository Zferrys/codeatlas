package com.codeatlas.server.entity;

import java.time.LocalDateTime;

public class ClassSummaryEntity {

    private Long id;
    private Long scanId;
    private Long projectId;
    private String fqn;
    private String simpleName;
    private String packageName;
    private String classType;
    private String layer;
    private Integer publicMethods;
    private Integer totalMethods;
    private Integer lineCount;
    private String annotations;
    private String dependencies;
    private String moduleName;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getScanId() { return scanId; }
    public void setScanId(Long scanId) { this.scanId = scanId; }
    public Long getProjectId() { return projectId; }
    public void setProjectId(Long projectId) { this.projectId = projectId; }
    public String getFqn() { return fqn; }
    public void setFqn(String fqn) { this.fqn = fqn; }
    public String getSimpleName() { return simpleName; }
    public void setSimpleName(String simpleName) { this.simpleName = simpleName; }
    public String getPackageName() { return packageName; }
    public void setPackageName(String packageName) { this.packageName = packageName; }
    public String getClassType() { return classType; }
    public void setClassType(String classType) { this.classType = classType; }
    public String getLayer() { return layer; }
    public void setLayer(String layer) { this.layer = layer; }
    public Integer getPublicMethods() { return publicMethods; }
    public void setPublicMethods(Integer publicMethods) { this.publicMethods = publicMethods; }
    public Integer getTotalMethods() { return totalMethods; }
    public void setTotalMethods(Integer totalMethods) { this.totalMethods = totalMethods; }
    public Integer getLineCount() { return lineCount; }
    public void setLineCount(Integer lineCount) { this.lineCount = lineCount; }
    public String getAnnotations() { return annotations; }
    public void setAnnotations(String annotations) { this.annotations = annotations; }
    public String getDependencies() { return dependencies; }
    public void setDependencies(String dependencies) { this.dependencies = dependencies; }
    public String getModuleName() { return moduleName; }
    public void setModuleName(String moduleName) { this.moduleName = moduleName; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
