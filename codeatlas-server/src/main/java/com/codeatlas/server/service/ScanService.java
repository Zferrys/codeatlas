package com.codeatlas.server.service;

import com.codeatlas.server.dto.response.ScanVO;

import java.util.List;

public interface ScanService {

    ScanVO triggerScan(Long projectId, Long userId);

    List<ScanVO> getScanHistory(Long projectId, Long userId);

    ScanVO getLatestScan(Long projectId, Long userId);
}
