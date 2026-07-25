package com.codeatlas.server.dto.response;

import com.codeatlas.server.entity.ViolationEntity;

import java.time.LocalDateTime;
import lombok.Data;

@Data
public class ViolationVO {

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


    public static ViolationVO from(ViolationEntity entity) {
        ViolationVO vo = new ViolationVO();
        vo.setId(entity.getId());
        vo.setScanId(entity.getScanId());
        vo.setRuleId(entity.getRuleId());
        vo.setProjectId(entity.getProjectId());
        vo.setSeverity(entity.getSeverity());
        vo.setClassFqn(entity.getClassFqn());
        vo.setMethodName(entity.getMethodName());
        vo.setLineNumber(entity.getLineNumber());
        vo.setMessage(entity.getMessage());
        vo.setSuggestion(entity.getSuggestion());
        vo.setIsResolved(entity.getIsResolved());
        vo.setResolvedAt(entity.getResolvedAt());
        vo.setCreatedAt(entity.getCreatedAt());
        return vo;
    }
}
