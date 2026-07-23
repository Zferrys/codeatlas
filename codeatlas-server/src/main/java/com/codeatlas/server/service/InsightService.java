package com.codeatlas.server.service;

import com.codeatlas.common.dto.PageResult;
import com.codeatlas.server.entity.InsightEntity;

import java.util.List;

public interface InsightService {

    void saveInsight(InsightEntity insight);

    PageResult<InsightEntity> getInsights(Long projectId, int page, int size);

    PageResult<InsightEntity> getInsightsByType(Long projectId, String type, int page, int size);

    InsightEntity getLatestArchStory(Long projectId);
}
