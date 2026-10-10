package com.codeatlas.server.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class SystemConfigEntity {

    private Long id;
    private String configKey;
    private String configValue;
    private String description;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
