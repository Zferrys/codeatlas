package com.codeatlas.server.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

@Data
public class AiAuditLogEntity {

    private Long id;
    private String traceId;
    private String model;
    private String stage;
    private Long projectId;
    private Long userId;
    private Integer promptTokens;
    private Integer completionTokens;
    private Integer totalTokens;
    private Integer latencyMs;
    private BigDecimal cost;
    private Boolean success;
    private String errorMessage;
    private LocalDateTime createdAt;

}
