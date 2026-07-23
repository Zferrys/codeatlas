package com.codeatlas.server.service.impl;

import com.codeatlas.common.dto.PageResult;
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
    public PageResult<InsightEntity> getInsights(Long projectId, int page, int size) {
        long total = insightMapper.countByProjectId(projectId);
        int offset = (page - 1) * size;
        List<InsightEntity> records = insightMapper.findByProjectIdPaged(projectId, offset, size);
        return new PageResult<>(records, total, page, size);
    }

    @Override
    public PageResult<InsightEntity> getInsightsByType(Long projectId, String type, int page, int size) {
        long total = insightMapper.countByProjectIdAndType(projectId, type);
        int offset = (page - 1) * size;
        List<InsightEntity> records = insightMapper.findByProjectIdAndTypePaged(projectId, type, offset, size);
        return new PageResult<>(records, total, page, size);
    }

    @Override
    public InsightEntity getLatestArchStory(Long projectId) {
        List<InsightEntity> list = insightMapper.findByProjectIdAndType(projectId, "ARCH_STORY");
        return list.isEmpty() ? null : list.get(0);
    }
}
