package com.codeatlas.server.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

@Data
public class InsightVO {

    private Long id;
    private Long scanId;
    private Long projectId;
    private String type;
    private String title;
    private String content;
    private BigDecimal confidence;
    private String sources;
    private String metadata;
    private LocalDateTime createdAt;


    public static InsightVO from(com.codeatlas.server.entity.InsightEntity entity) {
        InsightVO vo = new InsightVO();
        vo.setId(entity.getId());
        vo.setScanId(entity.getScanId());
        vo.setProjectId(entity.getProjectId());
        vo.setType(entity.getType());
        vo.setTitle(entity.getTitle());
        vo.setContent(entity.getContent());
        vo.setConfidence(entity.getConfidence());
        vo.setSources(entity.getSources());
        vo.setMetadata(entity.getMetadata());
        vo.setCreatedAt(entity.getCreatedAt());
        return vo;
    }
}
