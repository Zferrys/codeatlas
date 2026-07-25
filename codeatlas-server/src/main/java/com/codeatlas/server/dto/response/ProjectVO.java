package com.codeatlas.server.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
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

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LayerItem {
        private String name;
        private int count;
        private int percent;
        private String color;
    }
}
