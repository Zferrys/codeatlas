package com.codeatlas.server.service;

import com.codeatlas.server.dto.response.GraphVO;
import com.codeatlas.server.entity.Project;
import com.codeatlas.server.entity.ScanRecord;
import com.codeatlas.server.mapper.ProjectMapper;
import com.codeatlas.server.mapper.ScanMapper;
import com.codeatlas.server.service.impl.MapServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("MapServiceImpl 单元测试")
class MapServiceImplTest {

    private static final Long PROJECT_ID = 1L;
    private static final Long USER_ID = 7L;

    @Mock
    private ProjectMapper projectMapper;
    @Mock
    private ScanMapper scanMapper;
    @Mock
    private Neo4jGraphService neo4jGraphService;
    @Mock
    private ProjectAccessChecker projectAccessChecker;

    private MapServiceImpl mapService;

    @BeforeEach
    void setUp() {
        mapService = new MapServiceImpl(projectMapper, scanMapper, neo4jGraphService, projectAccessChecker);
        // 自注入：单元测试中直接指回自身，等价于代理自调用
        ReflectionTestUtils.setField(mapService, "self", mapService);
    }

    private ScanRecord scan(String status) {
        ScanRecord record = new ScanRecord();
        record.setId(100L);
        record.setProjectId(PROJECT_ID);
        record.setStatus(status);
        return record;
    }

    private GraphVO graphWithNode() {
        GraphVO graph = new GraphVO();
        GraphVO.NodeVO node = new GraphVO.NodeVO();
        node.setId("com.demo.AService");
        graph.setNodes(List.of(node));
        graph.setEdges(Collections.emptyList());
        return graph;
    }

    private void assertEmptyGraph(GraphVO graph) {
        assertNotNull(graph);
        assertEquals(List.of(), graph.getNodes());
        assertEquals(List.of(), graph.getEdges());
    }

    @Test
    @DisplayName("整图查询：先鉴权再走缓存方法，返回真实图")
    void getProjectMap_shouldCheckAccessFirstThenReturnGraph() {
        when(scanMapper.findLatestByProjectId(PROJECT_ID)).thenReturn(scan("COMPLETED"));
        GraphVO expected = graphWithNode();
        when(neo4jGraphService.queryFullGraph(PROJECT_ID)).thenReturn(expected);

        GraphVO result = mapService.getProjectMap(PROJECT_ID, USER_ID);

        var order = inOrder(projectAccessChecker, scanMapper);
        order.verify(projectAccessChecker).check(PROJECT_ID, USER_ID);
        order.verify(scanMapper).findLatestByProjectId(PROJECT_ID);
        assertSame(expected, result);
    }

    @Test
    @DisplayName("整图缓存方法：无扫描记录返回空图")
    void getProjectMapCached_noScan_shouldReturnEmptyGraph() {
        when(scanMapper.findLatestByProjectId(PROJECT_ID)).thenReturn(null);

        assertEmptyGraph(mapService.getProjectMapCached(PROJECT_ID));
        verifyNoInteractions(neo4jGraphService);
    }

    @Test
    @DisplayName("整图缓存方法：扫描未完成返回空图")
    void getProjectMapCached_scanNotCompleted_shouldReturnEmptyGraph() {
        when(scanMapper.findLatestByProjectId(PROJECT_ID)).thenReturn(scan("RUNNING"));

        assertEmptyGraph(mapService.getProjectMapCached(PROJECT_ID));
        verifyNoInteractions(neo4jGraphService);
    }

    @Test
    @DisplayName("整图缓存方法：Neo4j 返回含节点的图则原样返回")
    void getProjectMapCached_neo4jHasNodes_shouldReturnGraph() {
        when(scanMapper.findLatestByProjectId(PROJECT_ID)).thenReturn(scan("COMPLETED"));
        GraphVO expected = graphWithNode();
        when(neo4jGraphService.queryFullGraph(PROJECT_ID)).thenReturn(expected);

        assertSame(expected, mapService.getProjectMapCached(PROJECT_ID));
    }

    @Test
    @DisplayName("整图缓存方法：Neo4j 返回 nodes 为 null 的图返回空图")
    void getProjectMapCached_nullNodes_shouldReturnEmptyGraph() {
        when(scanMapper.findLatestByProjectId(PROJECT_ID)).thenReturn(scan("COMPLETED"));
        when(neo4jGraphService.queryFullGraph(PROJECT_ID)).thenReturn(new GraphVO());

        assertEmptyGraph(mapService.getProjectMapCached(PROJECT_ID));
    }

    @Test
    @DisplayName("整图缓存方法：Neo4j 返回空节点图返回空图")
    void getProjectMapCached_emptyNodes_shouldReturnEmptyGraph() {
        when(scanMapper.findLatestByProjectId(PROJECT_ID)).thenReturn(scan("COMPLETED"));
        GraphVO emptyFromNeo4j = new GraphVO();
        emptyFromNeo4j.setNodes(Collections.emptyList());
        emptyFromNeo4j.setEdges(Collections.emptyList());
        when(neo4jGraphService.queryFullGraph(PROJECT_ID)).thenReturn(emptyFromNeo4j);

        assertEmptyGraph(mapService.getProjectMapCached(PROJECT_ID));
    }

    @Test
    @DisplayName("整图缓存方法：Neo4j 抛异常时降级为空图")
    void getProjectMapCached_neo4jThrows_shouldReturnEmptyGraph() {
        when(scanMapper.findLatestByProjectId(PROJECT_ID)).thenReturn(scan("COMPLETED"));
        when(neo4jGraphService.queryFullGraph(PROJECT_ID))
                .thenThrow(new RuntimeException("neo4j down"));

        assertEmptyGraph(mapService.getProjectMapCached(PROJECT_ID));
    }

    @Test
    @DisplayName("分页整图：page/size 越界时钳制为 1 与 [10,500]，用 Project 重载鉴权")
    void getProjectMapPaged_shouldClampPageAndSize() {
        Project project = new Project();
        project.setId(PROJECT_ID);
        when(projectMapper.findById(PROJECT_ID)).thenReturn(project);
        when(scanMapper.findLatestByProjectId(PROJECT_ID)).thenReturn(scan("COMPLETED"));
        GraphVO expected = graphWithNode();
        when(neo4jGraphService.queryFullGraphPaged(PROJECT_ID, "class", 1, 500))
                .thenReturn(expected);

        GraphVO result = mapService.getProjectMapPaged(PROJECT_ID, "class", 0, 99999, USER_ID);

        verify(projectAccessChecker).check(project, PROJECT_ID, USER_ID);
        assertSame(expected, result);
    }

    @Test
    @DisplayName("分页整图：size 小于 10 时抬升为 10，页码生效")
    void getProjectMapPaged_smallSize_shouldRaiseToTen() {
        Project project = new Project();
        when(projectMapper.findById(PROJECT_ID)).thenReturn(project);
        when(scanMapper.findLatestByProjectId(PROJECT_ID)).thenReturn(scan("COMPLETED"));
        when(neo4jGraphService.queryFullGraphPaged(PROJECT_ID, null, 3, 10))
                .thenReturn(graphWithNode());

        mapService.getProjectMapPaged(PROJECT_ID, null, 3, 5, USER_ID);

        verify(neo4jGraphService).queryFullGraphPaged(PROJECT_ID, null, 3, 10);
    }

    @Test
    @DisplayName("分页整图：size 在区间内保持不变")
    void getProjectMapPaged_sizeWithinRange_shouldKeep() {
        Project project = new Project();
        when(projectMapper.findById(PROJECT_ID)).thenReturn(project);
        when(scanMapper.findLatestByProjectId(PROJECT_ID)).thenReturn(scan("COMPLETED"));
        when(neo4jGraphService.queryFullGraphPaged(PROJECT_ID, "class", 2, 100))
                .thenReturn(graphWithNode());

        mapService.getProjectMapPaged(PROJECT_ID, "class", 2, 100, USER_ID);

        verify(neo4jGraphService).queryFullGraphPaged(PROJECT_ID, "class", 2, 100);
    }

    @Test
    @DisplayName("分页整图：无扫描记录返回空图且不查 Neo4j")
    void getProjectMapPaged_noScan_shouldReturnEmptyGraph() {
        Project project = new Project();
        when(projectMapper.findById(PROJECT_ID)).thenReturn(project);
        when(scanMapper.findLatestByProjectId(PROJECT_ID)).thenReturn(null);

        assertEmptyGraph(mapService.getProjectMapPaged(PROJECT_ID, "class", 1, 20, USER_ID));
        verifyNoInteractions(neo4jGraphService);
    }

    @Test
    @DisplayName("分页整图：扫描未完成返回空图")
    void getProjectMapPaged_scanNotCompleted_shouldReturnEmptyGraph() {
        Project project = new Project();
        when(projectMapper.findById(PROJECT_ID)).thenReturn(project);
        when(scanMapper.findLatestByProjectId(PROJECT_ID)).thenReturn(scan("FAILED"));

        assertEmptyGraph(mapService.getProjectMapPaged(PROJECT_ID, "class", 1, 20, USER_ID));
        verifyNoInteractions(neo4jGraphService);
    }

    @Test
    @DisplayName("分页整图：Neo4j 抛异常降级为空图")
    void getProjectMapPaged_neo4jThrows_shouldReturnEmptyGraph() {
        Project project = new Project();
        when(projectMapper.findById(PROJECT_ID)).thenReturn(project);
        when(scanMapper.findLatestByProjectId(PROJECT_ID)).thenReturn(scan("COMPLETED"));
        when(neo4jGraphService.queryFullGraphPaged(anyLong(), any(), anyInt(), anyInt()))
                .thenThrow(new RuntimeException("down"));

        assertEmptyGraph(mapService.getProjectMapPaged(PROJECT_ID, "class", 1, 20, USER_ID));
    }

    @Test
    @DisplayName("子图：depth 低于下限钳制为 1")
    void getSubgraph_lowDepth_shouldClampToOne() {
        Project project = new Project();
        when(projectMapper.findById(PROJECT_ID)).thenReturn(project);
        when(neo4jGraphService.querySubgraph(PROJECT_ID, "com.demo.AService", 1))
                .thenReturn(graphWithNode());

        mapService.getSubgraph(PROJECT_ID, "com.demo.AService", 0, USER_ID);

        verify(projectAccessChecker).check(project, PROJECT_ID, USER_ID);
        verify(neo4jGraphService).querySubgraph(PROJECT_ID, "com.demo.AService", 1);
    }

    @Test
    @DisplayName("子图：depth 高于上限钳制为 5")
    void getSubgraph_highDepth_shouldClampToFive() {
        Project project = new Project();
        when(projectMapper.findById(PROJECT_ID)).thenReturn(project);
        when(neo4jGraphService.querySubgraph(PROJECT_ID, "com.demo.AService", 5))
                .thenReturn(graphWithNode());

        mapService.getSubgraph(PROJECT_ID, "com.demo.AService", 10, USER_ID);

        verify(neo4jGraphService).querySubgraph(PROJECT_ID, "com.demo.AService", 5);
    }

    @Test
    @DisplayName("子图：区间内 depth 原样透传并返回真实图")
    void getSubgraph_depthWithinRange_shouldPassThrough() {
        Project project = new Project();
        when(projectMapper.findById(PROJECT_ID)).thenReturn(project);
        GraphVO expected = graphWithNode();
        when(neo4jGraphService.querySubgraph(PROJECT_ID, "com.demo.AService", 3))
                .thenReturn(expected);

        assertSame(expected, mapService.getSubgraph(PROJECT_ID, "com.demo.AService", 3, USER_ID));
    }

    @Test
    @DisplayName("子图：Neo4j 抛异常降级为空图")
    void getSubgraph_neo4jThrows_shouldReturnEmptyGraph() {
        Project project = new Project();
        when(projectMapper.findById(PROJECT_ID)).thenReturn(project);
        when(neo4jGraphService.querySubgraph(PROJECT_ID, "com.demo.AService", 2))
                .thenThrow(new RuntimeException("down"));

        assertEmptyGraph(mapService.getSubgraph(PROJECT_ID, "com.demo.AService", 2, USER_ID));
    }
}
