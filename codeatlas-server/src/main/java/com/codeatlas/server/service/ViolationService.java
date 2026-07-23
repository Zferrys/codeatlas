package com.codeatlas.server.service;

import com.codeatlas.common.dto.PageResult;
import com.codeatlas.server.entity.ViolationEntity;

import java.util.List;

public interface ViolationService {

    PageResult<ViolationEntity> getViolations(Long projectId, int page, int size);

    List<ViolationEntity> getViolationsByScanId(Long scanId);

    void resolveViolation(Long violationId);
}
