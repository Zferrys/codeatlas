package com.codeatlas.server.service.impl;

import com.codeatlas.server.entity.InsightEntity;
import com.codeatlas.server.mapper.InsightMapper;
import com.codeatlas.server.service.InsightService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class InsightServiceImpl implements InsightService {

    private final InsightMapper insightMapper;

    public InsightServiceImpl(InsightMapper insightMapper) {
        this.insightMapper = insightMapper;
    }

    @Override
    @Transactional
    public void saveInsight(InsightEntity insight) {
        insightMapper.insert(insight);
    }

    @Override
    public List<InsightEntity> getInsights(Long projectId) {
        return insightMapper.findByProjectId(projectId);
    }

    @Override
    public List<InsightEntity> getInsightsByType(Long projectId, String type) {
        return insightMapper.findByProjectIdAndType(projectId, type);
    }

    @Override
    public InsightEntity getLatestArchStory(Long projectId) {
        List<InsightEntity> list = insightMapper.findByProjectIdAndType(projectId, "ARCH_STORY");
        return list.isEmpty() ? null : list.get(0);
    }
}
