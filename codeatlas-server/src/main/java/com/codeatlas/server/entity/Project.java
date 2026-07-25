package com.codeatlas.server.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

@Data
public class Project {

    private Long id;
    private String name;
    private String description;
    private String sourceType;
    private String sourceUrl;
    private String defaultBranch;
    private String language;
    private Integer totalClasses;
    private Integer totalModules;
    private BigDecimal healthScore;
    private Long lastScanId;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
