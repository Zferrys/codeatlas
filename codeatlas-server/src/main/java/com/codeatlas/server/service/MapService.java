package com.codeatlas.server.service;

import com.codeatlas.server.dto.response.GraphVO;

public interface MapService {

    GraphVO getProjectMap(Long projectId, Long userId);

    GraphVO getProjectMapPaged(Long projectId, String level, int page, int size, Long userId);

    GraphVO getSubgraph(Long projectId, String fqn, int depth, Long userId);
}
