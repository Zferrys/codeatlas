package com.codeatlas.server.service;

import com.codeatlas.common.dto.PageResult;
import com.codeatlas.server.dto.response.ScanVO;

public interface ScanService {

    ScanVO triggerScan(Long projectId, Long userId);

    PageResult<ScanVO> getScanHistory(Long projectId, Long userId, int page, int size);

    ScanVO getLatestScan(Long projectId, Long userId);
}
