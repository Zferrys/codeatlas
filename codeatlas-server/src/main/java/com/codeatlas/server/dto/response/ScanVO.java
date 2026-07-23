package com.codeatlas.server.dto.response;

import java.time.LocalDateTime;

public class ScanVO {

    private Long id;
    private Long projectId;
    private String commitHash;
    private String branch;
    private String status;
    private Integer totalClasses;
    private Integer totalLines;
    private Integer totalViolations;
    private Long durationMs;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getProjectId() { return projectId; }
    public void setProjectId(Long projectId) { this.projectId = projectId; }
    public String getCommitHash() { return commitHash; }
    public void setCommitHash(String commitHash) { this.commitHash = commitHash; }
    public String getBranch() { return branch; }
    public void setBranch(String branch) { this.branch = branch; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getTotalClasses() { return totalClasses; }
    public void setTotalClasses(Integer totalClasses) { this.totalClasses = totalClasses; }
    public Integer getTotalLines() { return totalLines; }
    public void setTotalLines(Integer totalLines) { this.totalLines = totalLines; }
    public Integer getTotalViolations() { return totalViolations; }
    public void setTotalViolations(Integer totalViolations) { this.totalViolations = totalViolations; }
    public Long getDurationMs() { return durationMs; }
    public void setDurationMs(Long durationMs) { this.durationMs = durationMs; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }
    public LocalDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(LocalDateTime completedAt) { this.completedAt = completedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
