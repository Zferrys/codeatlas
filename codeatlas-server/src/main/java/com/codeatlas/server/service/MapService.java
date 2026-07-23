package com.codeatlas.server.service;

import com.codeatlas.server.dto.response.GraphVO;

public interface MapService {

    GraphVO getProjectMap(Long projectId, Long userId);
}
