package com.codeatlas.server.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public class ProjectVO {

    private Long id;
    private String name;
    private String description;
    private String language;
    private String sourceType;
    private String sourceUrl;
    private BigDecimal healthScore;
    private Integer totalClasses;
    private Integer totalModules;
    private Integer totalScans;
    private Integer totalInsights;
    private Long lastScanId;
    private LocalDateTime lastScanTime;
    private LocalDateTime createdAt;
    private List<LayerItem> layerDistribution;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }
    public String getSourceType() { return sourceType; }
    public void setSourceType(String sourceType) { this.sourceType = sourceType; }
    public String getSourceUrl() { return sourceUrl; }
    public void setSourceUrl(String sourceUrl) { this.sourceUrl = sourceUrl; }
    public BigDecimal getHealthScore() { return healthScore; }
    public void setHealthScore(BigDecimal healthScore) { this.healthScore = healthScore; }
    public Integer getTotalClasses() { return totalClasses; }
    public void setTotalClasses(Integer totalClasses) { this.totalClasses = totalClasses; }
    public Integer getTotalModules() { return totalModules; }
    public void setTotalModules(Integer totalModules) { this.totalModules = totalModules; }
    public Integer getTotalScans() { return totalScans; }
    public void setTotalScans(Integer totalScans) { this.totalScans = totalScans; }
    public Integer getTotalInsights() { return totalInsights; }
    public void setTotalInsights(Integer totalInsights) { this.totalInsights = totalInsights; }
    public Long getLastScanId() { return lastScanId; }
    public void setLastScanId(Long lastScanId) { this.lastScanId = lastScanId; }
    public LocalDateTime getLastScanTime() { return lastScanTime; }
    public void setLastScanTime(LocalDateTime lastScanTime) { this.lastScanTime = lastScanTime; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public List<LayerItem> getLayerDistribution() { return layerDistribution; }
    public void setLayerDistribution(List<LayerItem> layerDistribution) { this.layerDistribution = layerDistribution; }

    public static class LayerItem {
        private String name;
        private int count;
        private int percent;
        private String color;

        public LayerItem() {}
        public LayerItem(String name, int count, int percent, String color) {
            this.name = name; this.count = count; this.percent = percent; this.color = color;
        }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public int getCount() { return count; }
        public void setCount(int count) { this.count = count; }
        public int getPercent() { return percent; }
        public void setPercent(int percent) { this.percent = percent; }
        public String getColor() { return color; }
        public void setColor(String color) { this.color = color; }
    }
}
