package com.codeatlas.server.service;

import com.codeatlas.server.entity.ViolationEntity;

import java.util.List;

public interface ViolationService {

    List<ViolationEntity> getViolations(Long projectId);

    List<ViolationEntity> getViolationsByScanId(Long scanId);

    void resolveViolation(Long violationId);
}
