package com.codeatlas.server.dto.response;

import java.time.LocalDateTime;
import lombok.Data;

@Data
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

}
