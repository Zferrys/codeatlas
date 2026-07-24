package com.codeatlas.server.service;

import com.codeatlas.common.dto.PageResult;
import com.codeatlas.server.dto.response.ScanVO;
import com.codeatlas.server.entity.ClassSummaryEntity;
import com.codeatlas.server.entity.ScanRecord;

import java.util.List;

public interface ScanService {

    ScanVO triggerScan(Long projectId, Long userId);

    ScanVO triggerIncrementalScan(Long projectId, Long userId);

    PageResult<ScanVO> getScanHistory(Long projectId, Long userId, int page, int size);

    ScanVO getLatestScan(Long projectId, Long userId);

    /** 获取最新扫描记录实体（内部使用） */
    ScanRecord getLatestScanEntity(Long projectId);

    /** 获取最新扫描的类汇总列表（内部使用） */
    List<ClassSummaryEntity> getClassSummaries(Long projectId);
}
