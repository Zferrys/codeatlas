package com.codeatlas.server.entity;

import java.time.LocalDateTime;
import lombok.Data;

@Data
public class ViolationEntity {

    private Long id;
    private Long scanId;
    private Long ruleId;
    private Long projectId;
    private String severity;
    private String classFqn;
    private String methodName;
    private Integer lineNumber;
    private String message;
    private String suggestion;
    private Boolean isResolved;
    private LocalDateTime resolvedAt;
    private LocalDateTime createdAt;

}
