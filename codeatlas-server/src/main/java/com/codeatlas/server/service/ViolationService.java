package com.codeatlas.server.service;

import com.codeatlas.common.dto.PageResult;
import com.codeatlas.server.entity.ViolationEntity;

import java.util.List;

public interface ViolationService {

    PageResult<ViolationEntity> getViolations(Long projectId, int page, int size, Long userId);

    List<ViolationEntity> getViolationsByScanId(Long scanId, Long userId);

    void resolveViolation(Long projectId, Long violationId, Long userId);
}
