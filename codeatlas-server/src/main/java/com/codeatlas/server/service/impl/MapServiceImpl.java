package com.codeatlas.server.service.impl;

import com.codeatlas.common.constant.ErrorCode;
import com.codeatlas.common.exception.BusinessException;
import com.codeatlas.server.dto.response.GraphVO;
import com.codeatlas.server.entity.Project;
import com.codeatlas.server.entity.ScanRecord;
import com.codeatlas.server.mapper.ProjectMapper;
import com.codeatlas.server.mapper.ScanMapper;
import com.codeatlas.server.service.MapService;
import com.codeatlas.server.service.Neo4jGraphService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Collections;

@Service
public class MapServiceImpl implements MapService {

    private static final Logger log = LoggerFactory.getLogger(MapServiceImpl.class);

    private final ProjectMapper projectMapper;
    private final ScanMapper scanMapper;
    private final Neo4jGraphService neo4jGraphService;

    public MapServiceImpl(ProjectMapper projectMapper, ScanMapper scanMapper,
                          Neo4jGraphService neo4jGraphService) {
        this.projectMapper = projectMapper;
        this.scanMapper = scanMapper;
        this.neo4jGraphService = neo4jGraphService;
    }

    @Override
    public GraphVO getProjectMap(Long projectId, Long userId) {
        Project project = projectMapper.findById(projectId);
        if (project == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "项目不存在");
        }

        ScanRecord latestScan = scanMapper.findLatestByProjectId(projectId);
        if (latestScan == null || !"COMPLETED".equals(latestScan.getStatus())) {
            GraphVO empty = new GraphVO();
            empty.setNodes(Collections.emptyList());
            empty.setEdges(Collections.emptyList());
            return empty;
        }

        // 优先从 Neo4j 查询依赖图
        try {
            GraphVO graph = neo4jGraphService.queryFullGraph(projectId);
            if (graph.getNodes() != null && !graph.getNodes().isEmpty()) {
                return graph;
            }
        } catch (Exception e) {
            log.warn("Neo4j graph query failed, returning empty: {}", e.getMessage());
        }

        GraphVO empty = new GraphVO();
        empty.setNodes(Collections.emptyList());
        empty.setEdges(Collections.emptyList());
        return empty;
    }

    @Override
    public GraphVO getSubgraph(Long projectId, String fqn, int depth, Long userId) {
        Project project = projectMapper.findById(projectId);
        if (project == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "项目不存在");
        }

        int actualDepth = Math.max(1, Math.min(depth, 5));
        try {
            return neo4jGraphService.querySubgraph(projectId, fqn, actualDepth);
        } catch (Exception e) {
            log.warn("Neo4j subgraph query failed for fqn={}: {}", fqn, e.getMessage());
            GraphVO empty = new GraphVO();
            empty.setNodes(Collections.emptyList());
            empty.setEdges(Collections.emptyList());
            return empty;
        }
    }
}
