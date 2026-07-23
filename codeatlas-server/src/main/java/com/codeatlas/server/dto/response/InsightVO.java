package com.codeatlas.server.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class InsightVO {

    private Long id;
    private Long scanId;
    private Long projectId;
    private String type;
    private String title;
    private String content;
    private BigDecimal confidence;
    private String sources;
    private String metadata;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getScanId() { return scanId; }
    public void setScanId(Long scanId) { this.scanId = scanId; }

    public Long getProjectId() { return projectId; }
    public void setProjectId(Long projectId) { this.projectId = projectId; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public BigDecimal getConfidence() { return confidence; }
    public void setConfidence(BigDecimal confidence) { this.confidence = confidence; }

    public String getSources() { return sources; }
    public void setSources(String sources) { this.sources = sources; }

    public String getMetadata() { return metadata; }
    public void setMetadata(String metadata) { this.metadata = metadata; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public static InsightVO from(com.codeatlas.server.entity.InsightEntity entity) {
        InsightVO vo = new InsightVO();
        vo.setId(entity.getId());
        vo.setScanId(entity.getScanId());
        vo.setProjectId(entity.getProjectId());
        vo.setType(entity.getType());
        vo.setTitle(entity.getTitle());
        vo.setContent(entity.getContent());
        vo.setConfidence(entity.getConfidence());
        vo.setSources(entity.getSources());
        vo.setMetadata(entity.getMetadata());
        vo.setCreatedAt(entity.getCreatedAt());
        return vo;
    }
}
