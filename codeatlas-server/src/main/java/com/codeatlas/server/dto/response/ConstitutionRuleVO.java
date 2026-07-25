package com.codeatlas.server.dto.response;

import com.codeatlas.server.entity.ConstitutionRuleEntity;

import java.time.LocalDateTime;
import lombok.Data;

@Data
public class ConstitutionRuleVO {

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


    public static ConstitutionRuleVO from(ConstitutionRuleEntity entity) {
        ConstitutionRuleVO vo = new ConstitutionRuleVO();
        vo.setId(entity.getId());
        vo.setProjectId(entity.getProjectId());
        vo.setName(entity.getName());
        vo.setDescription(entity.getDescription());
        vo.setCategory(entity.getCategory());
        vo.setSeverity(entity.getSeverity());
        vo.setRuleDefinition(entity.getRuleDefinition());
        vo.setIsEnabled(entity.getIsEnabled());
        vo.setVersion(entity.getVersion());
        vo.setCreatedAt(entity.getCreatedAt());
        return vo;
    }
}
