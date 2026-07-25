package com.codeatlas.server.entity;

import java.time.LocalDateTime;
import lombok.Data;

@Data
public class ScanRecord {

    private Long id;
    private Long projectId;
    private String commitHash;
    private String branch;
    private String status;
    private Integer totalClasses;
    private Integer totalLines;
    private Integer totalViolations;
    private Long durationMs;
    private String errorMessage;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private LocalDateTime createdAt;

}
