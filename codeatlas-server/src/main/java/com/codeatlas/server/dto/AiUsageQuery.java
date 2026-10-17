package com.codeatlas.server.dto;

import lombok.Data;

/**
 * AI Token 流水查询条件：分页 + 可选筛选（模型/阶段/使用者/成功状态/时间范围）。
 * 时间格式为 yyyy-MM-dd HH:mm:ss，语义为左闭右开 [startTime, endTime)。
 */
@Data
public class AiUsageQuery {

    private int page = 1;
    private int size = 20;
    private String model;
    private String stage;
    private Long userId;
    private String username;
    private Boolean success;
    private String startTime;
    private String endTime;
}
