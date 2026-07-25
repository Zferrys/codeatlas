package com.codeatlas.server.entity;

import java.time.LocalDateTime;
import lombok.Data;

@Data
public class ConstitutionRuleEntity {

    private Long id;
    private Long projectId;
    private String name;
    private String description;
    private String category;
    private String severity;
    private String ruleDefinition;
    private Boolean isEnabled;
    private Integer version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

}
