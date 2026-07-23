package com.codeatlas.server.service.impl;

import com.codeatlas.common.constant.ErrorCode;
import com.codeatlas.common.exception.BusinessException;
import com.codeatlas.server.dto.response.GraphVO;
import com.codeatlas.server.entity.ClassSummaryEntity;
import com.codeatlas.server.entity.Project;
import com.codeatlas.server.entity.ScanRecord;
import com.codeatlas.server.mapper.ClassSummaryMapper;
import com.codeatlas.server.mapper.ProjectMapper;
import com.codeatlas.server.mapper.ScanMapper;
import com.codeatlas.server.service.MapService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class MapServiceImpl implements MapService {

    private static final Logger log = LoggerFactory.getLogger(MapServiceImpl.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final ProjectMapper projectMapper;
    private final ScanMapper scanMapper;
    private final ClassSummaryMapper classSummaryMapper;

    public MapServiceImpl(ProjectMapper projectMapper, ScanMapper scanMapper,
                          ClassSummaryMapper classSummaryMapper) {
        this.projectMapper = projectMapper;
        this.scanMapper = scanMapper;
        this.classSummaryMapper = classSummaryMapper;
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

        List<ClassSummaryEntity> classes = classSummaryMapper.findByScanId(latestScan.getId());
        if (classes.isEmpty()) {
            GraphVO empty = new GraphVO();
            empty.setNodes(Collections.emptyList());
            empty.setEdges(Collections.emptyList());
            return empty;
        }

        Set<String> fqnSet = classes.stream()
                .map(ClassSummaryEntity::getFqn)
                .collect(Collectors.toSet());

        // 构建节点
        List<GraphVO.NodeVO> nodes = new ArrayList<>();
        for (ClassSummaryEntity cls : classes) {
            GraphVO.NodeVO node = new GraphVO.NodeVO();
            node.setId(cls.getFqn());
            node.setLabel(cls.getSimpleName());
            node.setGroup(cls.getLayer() != null ? cls.getLayer().toLowerCase() : "unknown");
            node.setLayer(cls.getLayer());
            node.setMethods(cls.getTotalMethods() != null ? cls.getTotalMethods() : 0);
            node.setFields(0);
            node.setLineCount(cls.getLineCount() != null ? cls.getLineCount() : 0);
            nodes.add(node);
        }

        // 构建边 — 从 dependencies JSON 解析
        List<GraphVO.EdgeVO> edges = new ArrayList<>();
        for (ClassSummaryEntity cls : classes) {
            List<String> deps = parseDependencies(cls.getDependencies());
            for (String depFqn : deps) {
                if (fqnSet.contains(depFqn)) {
                    GraphVO.EdgeVO edge = new GraphVO.EdgeVO();
                    edge.setSource(cls.getFqn());
                    edge.setTarget(depFqn);
                    edge.setType("dependency");
                    edges.add(edge);
                }
            }
        }

        GraphVO graph = new GraphVO();
        graph.setNodes(nodes);
        graph.setEdges(edges);
        log.info("Map generated: projectId={}, nodes={}, edges={}", projectId, nodes.size(), edges.size());
        return graph;
    }

    private List<String> parseDependencies(String json) {
        if (json == null || json.isEmpty() || "[]".equals(json)) return Collections.emptyList();
        try {
            return OBJECT_MAPPER.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }
}
