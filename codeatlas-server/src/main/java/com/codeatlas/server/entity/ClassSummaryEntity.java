package com.codeatlas.server.entity;

import java.time.LocalDateTime;
import lombok.Data;

@Data
public class ClassSummaryEntity {

    private Long id;
    private Long scanId;
    private Long projectId;
    private String fqn;
    private String simpleName;
    private String packageName;
    private String classType;
    private String layer;
    private Integer publicMethods;
    private Integer totalMethods;
    private Integer lineCount;
    private String annotations;
    private String dependencies;
    private String moduleName;
    private LocalDateTime createdAt;

}
