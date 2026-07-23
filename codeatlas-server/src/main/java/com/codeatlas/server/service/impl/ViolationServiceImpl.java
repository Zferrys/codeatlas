package com.codeatlas.server.service.impl;

import com.codeatlas.common.constant.ErrorCode;
import com.codeatlas.common.dto.PageResult;
import com.codeatlas.common.exception.BusinessException;
import com.codeatlas.server.entity.ViolationEntity;
import com.codeatlas.server.mapper.ViolationMapper;
import com.codeatlas.server.service.ViolationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ViolationServiceImpl implements ViolationService {

    private static final Logger log = LoggerFactory.getLogger(ViolationServiceImpl.class);

    private final ViolationMapper mapper;

    public ViolationServiceImpl(ViolationMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public PageResult<ViolationEntity> getViolations(Long projectId, int page, int size) {
        long total = mapper.countByProjectId(projectId);
        int offset = (page - 1) * size;
        List<ViolationEntity> records = mapper.findByProjectIdPaged(projectId, offset, size);
        return new PageResult<>(records, total, page, size);
    }

    @Override
    public List<ViolationEntity> getViolationsByScanId(Long scanId) {
        return mapper.findByScanId(scanId);
    }

    @Override
    @Transactional
    public void resolveViolation(Long violationId) {
        int affected = mapper.resolve(violationId);
        if (affected == 0) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "违规记录不存在");
        }
        log.info("Violation resolved: id={}", violationId);
    }
}
