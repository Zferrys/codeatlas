package com.codeatlas.server.service;

import com.codeatlas.engine.parser.ClassSummaryResult;
import com.codeatlas.server.dto.response.GraphVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.neo4j.driver.Value;
import org.neo4j.driver.types.Node;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Neo4jGraphService 单元测试：mock Neo4j Driver 会话链（Session/Result/Record/Value/Node），
 * 对每条 Cypher 语句、参数列表、结果映射与全部异常包装分支做精确断言。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Neo4jGraphService 单元测试（Cypher 语句与参数精确断言 + 结果映射全分支）")
class Neo4jGraphServiceTest {

    private static final Long PROJECT_ID = 7L;

    @Mock
    private Driver neo4jDriver;
    @Mock
    private Session session;

    private Neo4jGraphService service;

    @BeforeEach
    void setUp() {
        service = new Neo4jGraphService(neo4jDriver);
    }

    // ==================== importGraph ====================

    @Test
    @DisplayName("导入空类列表：仅执行旧数据删除，不再执行任何批量写")
    void importGraph_emptyClasses_shouldOnlyDeleteOldNodes() {
        when(neo4jDriver.session()).thenReturn(session);

        service.importGraph(11L, Collections.emptyList());

        ArgumentCaptor<String> queryCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Value> valueCaptor = ArgumentCaptor.forClass(Value.class);
        verify(session, times(1)).run(queryCaptor.capture(), valueCaptor.capture());
        assertEquals("MATCH (c:Class {projectId: $projectId}) DETACH DELETE c", queryCaptor.getValue());
        assertEquals(Collections.singletonMap("projectId", 11L), valueCaptor.getValue().asMap());
        verify(session).close();
    }

    @Test
    @DisplayName("导入全量数据：按 删除→节点→依赖边→继承边→实现边 顺序执行五条 Cypher，参数逐字段断言")
    void importGraph_fullData_shouldRunBatchCypherInOrder() {
        when(neo4jDriver.session()).thenReturn(session);
        Result noResult = emptyResult();
        when(session.run(anyString(), any(Value.class))).thenReturn(noResult);

        ClassSummaryResult a = classResult("com.x.A", "A", "com.x", "service", "CLASS", 2, 5, 80);
        a.setDependencies(Arrays.asList("com.x.B", "com.external.Ext", null));
        a.setExtendedTypes(Arrays.asList("com.x.B", null, "com.external.Base"));
        a.setImplementedTypes(Arrays.asList("com.external.Itf", null));

        ClassSummaryResult b = classResult("com.x.B", "B", "com.x", "controller", "CLASS", 3, 7, 99);
        b.setDependencies(Collections.singletonList("com.x.A"));
        b.setExtendedTypes(Collections.emptyList());
        b.setImplementedTypes(Collections.singletonList("com.x.C"));

        ClassSummaryResult c = classResult("com.x.C", "C", "com.x", null, "INTERFACE", 1, 2, 10);
        c.setDependencies(Collections.emptyList());
        c.setExtendedTypes(null);
        c.setImplementedTypes(null);

        service.importGraph(PROJECT_ID, Arrays.asList(a, b, c));

        ArgumentCaptor<String> queryCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Value> valueCaptor = ArgumentCaptor.forClass(Value.class);
        verify(session, times(5)).run(queryCaptor.capture(), valueCaptor.capture());
        List<String> queries = queryCaptor.getAllValues();

        assertEquals("MATCH (c:Class {projectId: $projectId}) DETACH DELETE c", queries.get(0));
        assertEquals(Collections.singletonMap("projectId", PROJECT_ID), valueCaptor.getAllValues().get(0).asMap());

        assertEquals("UNWIND $nodes AS node MERGE (c:Class {fqn: node.fqn}) "
                        + "SET c.projectId = node.projectId, c.simpleName = node.simpleName, "
                        + "c.packageName = node.packageName, c.layer = node.layer, "
                        + "c.classType = node.classType, c.publicMethods = node.publicMethods, "
                        + "c.totalMethods = node.totalMethods, c.lineCount = node.lineCount",
                normalize(queries.get(1)));

        List<Map<String, Object>> nodes = paramList(valueCaptor.getAllValues().get(1), "nodes");
        assertEquals(3, nodes.size());
        Map<String, Object> expectedA = new HashMap<>();
        expectedA.put("fqn", "com.x.A");
        expectedA.put("projectId", PROJECT_ID);
        expectedA.put("simpleName", "A");
        expectedA.put("packageName", "com.x");
        expectedA.put("layer", "service");
        expectedA.put("classType", "CLASS");
        // Neo4j 驱动在 Value 转换时会把 int 升为 Long，按实际转换后的类型断言
        expectedA.put("publicMethods", 2L);
        expectedA.put("totalMethods", 5L);
        expectedA.put("lineCount", 80L);
        assertEquals(expectedA, nodes.get(0));
        assertEquals("com.x.C", nodes.get(2).get("fqn"));
        assertNull(nodes.get(2).get("layer"));

        assertEquals("UNWIND $edges AS edge MATCH (a:Class {fqn: edge.source}) "
                        + "MATCH (b:Class {fqn: edge.target}) MERGE (a)-[:DEPENDS_ON]->(b)",
                normalize(queries.get(2)));
        List<Map<String, Object>> dependEdges = paramList(valueCaptor.getAllValues().get(2), "edges");
        assertEquals(2, dependEdges.size());
        assertEquals("com.x.A", dependEdges.get(0).get("source"));
        assertEquals("com.x.B", dependEdges.get(0).get("target"));
        assertEquals("com.x.B", dependEdges.get(1).get("source"));
        assertEquals("com.x.A", dependEdges.get(1).get("target"));

        assertEquals("UNWIND $edges AS edge MATCH (a:Class {fqn: edge.source}) "
                        + "MATCH (b:Class {fqn: edge.target}) MERGE (a)-[:EXTENDS]->(b)",
                normalize(queries.get(3)));
        List<Map<String, Object>> extendsEdges = paramList(valueCaptor.getAllValues().get(3), "edges");
        assertEquals(1, extendsEdges.size());
        assertEquals("com.x.A", extendsEdges.get(0).get("source"));
        assertEquals("com.x.B", extendsEdges.get(0).get("target"));

        assertEquals("UNWIND $edges AS edge MATCH (a:Class {fqn: edge.source}) "
                        + "MATCH (b:Class {fqn: edge.target}) MERGE (a)-[:IMPLEMENTS]->(b)",
                normalize(queries.get(4)));
        List<Map<String, Object>> implementsEdges = paramList(valueCaptor.getAllValues().get(4), "edges");
        assertEquals(1, implementsEdges.size());
        assertEquals("com.x.B", implementsEdges.get(0).get("source"));
        assertEquals("com.x.C", implementsEdges.get(0).get("target"));
        verify(session).close();
    }

    @Test
    @DisplayName("无边数据：仅执行删除与节点批量写，三个边查询全部跳过")
    void importGraph_noEdges_shouldSkipAllEdgeQueries() {
        when(neo4jDriver.session()).thenReturn(session);
        Result noResult = emptyResult();
        when(session.run(anyString(), any(Value.class))).thenReturn(noResult);

        ClassSummaryResult only = classResult("com.x.Only", "Only", "com.x", "util", "CLASS", 1, 1, 5);
        only.setDependencies(Collections.emptyList());
        only.setExtendedTypes(Collections.emptyList());
        only.setImplementedTypes(Collections.emptyList());

        service.importGraph(PROJECT_ID, Collections.singletonList(only));

        ArgumentCaptor<String> queryCaptor = ArgumentCaptor.forClass(String.class);
        verify(session, times(2)).run(queryCaptor.capture(), any(Value.class));
        assertTrue(queryCaptor.getAllValues().get(1).contains("UNWIND $nodes AS node"));
    }

    @Test
    @DisplayName("依赖列表为 null：写入节点后边构建抛 NPE，被顶层 catch 吞掉不向外抛")
    void importGraph_nullDependencies_shouldSwallowNpe() {
        when(neo4jDriver.session()).thenReturn(session);
        Result noResult = emptyResult();
        when(session.run(anyString(), any(Value.class))).thenReturn(noResult);

        ClassSummaryResult cls = classResult("com.x.A", "A", "com.x", "service", "CLASS", 1, 1, 5);
        cls.setDependencies(null);

        assertDoesNotThrow(() -> service.importGraph(PROJECT_ID, Collections.singletonList(cls)));
        verify(session).close();
    }

    @Test
    @DisplayName("驱动获取会话抛异常：异常被吞掉，不向外抛")
    void importGraph_driverSessionThrows_shouldSwallowException() {
        when(neo4jDriver.session()).thenThrow(new RuntimeException("neo4j down"));

        assertDoesNotThrow(() -> service.importGraph(PROJECT_ID,
                Collections.singletonList(classResult("com.x.A", "A", "com.x", "service", "CLASS", 1, 1, 5))));
    }

    @Test
    @DisplayName("Cypher 执行失败：try-with-resources 仍关闭会话，异常不向外抛")
    void importGraph_cypherFails_shouldSwallowException() {
        when(neo4jDriver.session()).thenReturn(session);
        when(session.run(anyString(), any(Value.class))).thenThrow(new RuntimeException("write failed"));

        assertDoesNotThrow(() -> service.importGraph(PROJECT_ID,
                Collections.singletonList(classResult("com.x.A", "A", "com.x", "service", "CLASS", 1, 1, 5))));
        verify(session).close();
    }

    @Test
    @DisplayName("会话正常路径关闭失败：关闭异常经 try-with-resources 传播后被 catch 吞掉")
    void importGraph_closeFails_shouldSwallowException() {
        when(neo4jDriver.session()).thenReturn(session);
        doThrow(new RuntimeException("close failed")).when(session).close();

        assertDoesNotThrow(() -> service.importGraph(PROJECT_ID, Collections.emptyList()));
        verify(session).close();
    }

    // ==================== queryFullGraph ====================

    @Test
    @DisplayName("整图查询：单次返回节点与边，节点字段/分组/边类型逐项映射")
    void queryFullGraph_singleRecord_shouldMapNodesAndEdges() {
        when(neo4jDriver.session()).thenReturn(session);
        Node nodeA = fullNode("com.x.A", "A", "Service", "CLASS", 8, 120);
        Node nodeB = nodeWithProps(Collections.singletonMap("fqn", stringValue("com.x.B")));

        Map<String, Object> extends1 = new HashMap<>();
        extends1.put("source", "com.x.A");
        extends1.put("target", "com.x.B");
        extends1.put("relType", "EXTENDS");
        Map<String, Object> implements1 = new HashMap<>();
        implements1.put("source", "com.x.B");
        implements1.put("target", "com.x.A");
        implements1.put("relType", "IMPLEMENTS");
        Map<String, Object> depends = new HashMap<>();
        depends.put("source", "com.x.B");
        depends.put("target", "com.x.C");
        depends.put("relType", "DEPENDS_ON");
        Map<String, Object> nullRel = new HashMap<>();
        nullRel.put("source", "com.x.C");
        nullRel.put("target", "com.x.D");
        nullRel.put("relType", null);
        Map<String, Object> badEdge = new HashMap<>();
        badEdge.put("source", null);
        badEdge.put("target", "com.x.E");
        badEdge.put("relType", "EXTENDS");
        Map<String, Object> badEdge2 = new HashMap<>();
        badEdge2.put("source", "com.x.F");
        badEdge2.put("target", null);
        badEdge2.put("relType", "DEPENDS_ON");
        List<Object> edgeValues = new ArrayList<>();
        edgeValues.add(extends1);
        edgeValues.add(implements1);
        edgeValues.add(depends);
        edgeValues.add(nullRel);
        edgeValues.add(badEdge);
        edgeValues.add(badEdge2);

        Map<String, Value> recordValues = new HashMap<>();
        recordValues.put("nodes", listValue(Arrays.<Object>asList(nodeA, nodeB)));
        recordValues.put("edges", listValue(edgeValues));
        Result result = resultOf(recordOf(recordValues));
        when(session.run(anyString(), any(Value.class))).thenReturn(result);

        GraphVO graph = service.queryFullGraph(5L);

        assertEquals(2, graph.getNodes().size());
        GraphVO.NodeVO voA = graph.getNodes().get(0);
        assertEquals("com.x.A", voA.getId());
        assertEquals("A", voA.getLabel());
        assertEquals("service", voA.getGroup());
        assertEquals("Service", voA.getLayer());
        assertEquals("CLASS", voA.getClassType());
        assertEquals(8, voA.getMethods());
        assertEquals(120, voA.getLineCount());
        GraphVO.NodeVO voB = graph.getNodes().get(1);
        assertEquals("com.x.B", voB.getId());
        assertNull(voB.getLabel());
        assertEquals("unknown", voB.getGroup());
        assertNull(voB.getLayer());
        assertEquals(0, voB.getMethods());
        assertEquals(0, voB.getLineCount());

        assertEquals(4, graph.getEdges().size());
        assertEquals("extends", graph.getEdges().get(0).getType());
        assertEquals("com.x.A", graph.getEdges().get(0).getSource());
        assertEquals("com.x.B", graph.getEdges().get(0).getTarget());
        assertEquals("implements", graph.getEdges().get(1).getType());
        assertEquals("dependency", graph.getEdges().get(2).getType());
        assertEquals("dependency", graph.getEdges().get(3).getType());
        assertEquals("com.x.C", graph.getEdges().get(3).getSource());
        assertNull(graph.getTotalNodes());
        assertNull(graph.getPage());

        ArgumentCaptor<String> queryCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Value> valueCaptor = ArgumentCaptor.forClass(Value.class);
        verify(session).run(queryCaptor.capture(), valueCaptor.capture());
        assertEquals("MATCH (c:Class {projectId: $projectId}) WITH collect(c) AS nodes "
                + "OPTIONAL MATCH (a:Class {projectId: $projectId})-[r:DEPENDS_ON|EXTENDS|IMPLEMENTS]->(b:Class {projectId: $projectId}) "
                + "RETURN nodes, collect({source: a.fqn, target: b.fqn, relType: type(r)}) AS edges",
                normalize(queryCaptor.getValue()));
        assertEquals(Collections.singletonMap("projectId", 5L), valueCaptor.getValue().asMap());
        verify(session).close();
    }

    @Test
    @DisplayName("整图查询无结果：返回空节点空边图，不抛异常")
    void queryFullGraph_emptyResult_shouldReturnEmptyGraph() {
        when(neo4jDriver.session()).thenReturn(session);
        Result noResult = emptyResult();
        when(session.run(anyString(), any(Value.class))).thenReturn(noResult);

        GraphVO graph = service.queryFullGraph(PROJECT_ID);

        assertTrue(graph.getNodes().isEmpty());
        assertTrue(graph.getEdges().isEmpty());
        assertNull(graph.getTotalNodes());
    }

    @Test
    @DisplayName("节点属性读取异常：safeProp 返回 null、safePropInt 返回 0")
    void queryFullGraph_propertyAccessErrors_shouldFallbackToNullAndZero() {
        when(neo4jDriver.session()).thenReturn(session);
        Value badString = mock(Value.class);
        when(badString.asString()).thenThrow(new RuntimeException("bad string"));
        Value badInt = mock(Value.class);
        when(badInt.asInt()).thenThrow(new RuntimeException("bad int"));

        Node node = mock(Node.class);
        lenient().when(node.containsKey(anyString())).thenReturn(false);
        when(node.containsKey("classType")).thenReturn(true);
        when(node.get("classType")).thenReturn(badString);
        when(node.containsKey("totalMethods")).thenReturn(true);
        when(node.get("totalMethods")).thenReturn(badInt);
        when(node.containsKey("lineCount")).thenReturn(true);
        when(node.get("lineCount")).thenThrow(new RuntimeException("get failed"));
        Value fqnValue = stringValue("com.x.Bad");
        when(node.get("fqn")).thenReturn(fqnValue);

        Map<String, Value> recordValues = new HashMap<>();
        recordValues.put("nodes", listValue(Arrays.<Object>asList(node)));
        recordValues.put("edges", listValue(new ArrayList<>()));
        Result result = resultOf(recordOf(recordValues));
        when(session.run(anyString(), any(Value.class))).thenReturn(result);

        GraphVO graph = service.queryFullGraph(PROJECT_ID);

        assertEquals(1, graph.getNodes().size());
        GraphVO.NodeVO vo = graph.getNodes().get(0);
        assertEquals("com.x.Bad", vo.getId());
        assertNull(vo.getLabel());
        assertNull(vo.getLayer());
        assertEquals("unknown", vo.getGroup());
        assertNull(vo.getClassType());
        assertEquals(0, vo.getMethods());
        assertEquals(0, vo.getLineCount());
    }

    @Test
    @DisplayName("整图查询驱动异常：返回空图兜底")
    void queryFullGraph_driverFails_shouldReturnEmptyGraph() {
        when(neo4jDriver.session()).thenThrow(new RuntimeException("neo4j down"));

        GraphVO graph = service.queryFullGraph(PROJECT_ID);

        assertTrue(graph.getNodes().isEmpty());
        assertTrue(graph.getEdges().isEmpty());
    }

    // ==================== querySubgraph ====================

    @Test
    @DisplayName("子图 BFS depth=1：根节点 + 一层邻居，边按 dependency 类型记录，缺失节点跳过")
    void querySubgraph_depth1_shouldTraverseNeighborsBfs() {
        when(neo4jDriver.session()).thenReturn(session);
        when(session.run(anyString(), any(Value.class))).thenAnswer(invocation -> {
            String query = invocation.getArgument(0);
            Value params = invocation.getArgument(1);
            String currentFqn = (String) params.asMap().get("fqn");
            if (query.contains("UNION")) {
                Map<String, Value> target1 = new HashMap<>();
                target1.put("target", stringValue("com.x.B"));
                Map<String, Value> target2 = new HashMap<>();
                target2.put("target", stringValue("com.x.C"));
                return resultOf(recordOf(target1), recordOf(target2));
            }
            if ("com.x.B".equals(currentFqn)) {
                return resultOf(recordOf(Collections.singletonMap("c",
                        nodeValue(fullNode("com.x.B", "B", "Controller", "CLASS", 4, 60)))));
            }
            if ("com.x.C".equals(currentFqn)) {
                return emptyResult();
            }
            return resultOf(recordOf(Collections.singletonMap("c",
                    nodeValue(fullNode("com.x.A", "A", "Service", "CLASS", 2, 30)))));
        });

        GraphVO graph = service.querySubgraph(PROJECT_ID, "com.x.A", 1);

        assertEquals(2, graph.getNodes().size());
        assertEquals("com.x.A", graph.getNodes().get(0).getId());
        assertEquals("service", graph.getNodes().get(0).getGroup());
        assertEquals("com.x.B", graph.getNodes().get(1).getId());
        assertEquals("controller", graph.getNodes().get(1).getGroup());
        assertEquals(4, graph.getNodes().get(1).getMethods());
        assertEquals(60, graph.getNodes().get(1).getLineCount());

        assertEquals(2, graph.getEdges().size());
        assertEquals("com.x.A", graph.getEdges().get(0).getSource());
        assertEquals("com.x.B", graph.getEdges().get(0).getTarget());
        assertEquals("dependency", graph.getEdges().get(0).getType());
        assertEquals("com.x.C", graph.getEdges().get(1).getTarget());

        verify(session, times(1)).run(argThat(q -> q.contains("UNION")),
                argThat((Value v) -> "com.x.A".equals(v.asMap().get("fqn"))));
        verify(session, times(3)).run(argThat(q -> q.contains("RETURN c")), any(Value.class));
        verify(session).close();
    }

    @Test
    @DisplayName("子图 depth=0：只查询根节点，不再查询邻接边；缺失 layer 归入 unknown 分组")
    void querySubgraph_depth0_shouldOnlyQueryRootNode() {
        when(neo4jDriver.session()).thenReturn(session);
        Result rootResult = resultOf(recordOf(Collections.singletonMap("c",
                nodeValue(fullNode("com.x.A", "A", null, "CLASS", 2, 30)))));
        when(session.run(anyString(), any(Value.class))).thenReturn(rootResult);

        GraphVO graph = service.querySubgraph(PROJECT_ID, "com.x.A", 0);

        assertEquals(1, graph.getNodes().size());
        assertEquals("com.x.A", graph.getNodes().get(0).getId());
        assertEquals("unknown", graph.getNodes().get(0).getGroup());
        assertNull(graph.getNodes().get(0).getLayer());
        assertTrue(graph.getEdges().isEmpty());
        verify(session, times(1)).run(anyString(), any(Value.class));
    }

    @Test
    @DisplayName("子图根节点不存在且邻接为空：BFS 因当前层为空提前结束，返回空图")
    void querySubgraph_rootMissing_shouldReturnEmptyGraph() {
        when(neo4jDriver.session()).thenReturn(session);
        Result noResult = emptyResult();
        when(session.run(anyString(), any(Value.class))).thenReturn(noResult);

        GraphVO graph = service.querySubgraph(PROJECT_ID, "com.x.Missing", 2);

        assertTrue(graph.getNodes().isEmpty());
        assertTrue(graph.getEdges().isEmpty());
        verify(session, times(2)).run(anyString(), any(Value.class));
    }

    @Test
    @DisplayName("子图自环：已访问节点跳过，不产生自依赖边")
    void querySubgraph_selfLoop_shouldSkipVisitedNode() {
        when(neo4jDriver.session()).thenReturn(session);
        when(session.run(anyString(), any(Value.class))).thenAnswer(invocation -> {
            String query = invocation.getArgument(0);
            if (query.contains("UNION")) {
                return resultOf(recordOf(Collections.singletonMap("target", stringValue("com.x.A"))));
            }
            return resultOf(recordOf(Collections.singletonMap("c",
                    nodeValue(fullNode("com.x.A", "A", "Service", "CLASS", 2, 30)))));
        });

        GraphVO graph = service.querySubgraph(PROJECT_ID, "com.x.A", 1);

        assertEquals(1, graph.getNodes().size());
        assertTrue(graph.getEdges().isEmpty());
        verify(session, times(1)).run(argThat(q -> q.contains("RETURN c")), any(Value.class));
    }

    @Test
    @DisplayName("子图查询驱动异常：返回空图兜底")
    void querySubgraph_driverFails_shouldReturnEmptyGraph() {
        when(neo4jDriver.session()).thenThrow(new RuntimeException("neo4j down"));

        GraphVO graph = service.querySubgraph(PROJECT_ID, "com.x.A", 2);

        assertTrue(graph.getNodes().isEmpty());
        assertTrue(graph.getEdges().isEmpty());
    }

    // ==================== queryFullGraphPaged（CLASS/PACKAGE/MODULE） ====================

    @Test
    @DisplayName("分页 CLASS 级（level 为 null 走默认）：总数/分页/节点/页内边逐字段断言")
    void queryFullGraphPaged_nullLevel_shouldUseClassLevel() {
        when(neo4jDriver.session()).thenReturn(session);
        when(session.run(anyString(), any(Value.class))).thenAnswer(invocation -> {
            String query = invocation.getArgument(0);
            if (query.contains("count(c) AS total")) {
                return resultOf(recordOf(Collections.singletonMap("total", longValue(5L))));
            }
            if (query.contains("pageFqns")) {
                Map<String, Value> edge = new HashMap<>();
                edge.put("source", stringValue("com.x.C1"));
                edge.put("target", stringValue("com.x.C2"));
                return resultOf(recordOf(edge));
            }
            return resultOf(
                    recordOf(Collections.singletonMap("c",
                            nodeValue(fullNode("com.x.C1", "C1", "Service", "CLASS", 3, 40)))),
                    recordOf(Collections.singletonMap("c",
                            nodeValue(fullNode("com.x.C2", "C2", null, null, 1, 5)))));
        });

        GraphVO graph = service.queryFullGraphPaged(PROJECT_ID, null, 1, 2);

        assertEquals(5L, graph.getTotalNodes());
        assertEquals(1, graph.getPage());
        assertEquals(2, graph.getSize());
        assertEquals(Boolean.TRUE, graph.getHasMore());
        assertEquals(2, graph.getNodes().size());
        assertEquals("com.x.C1", graph.getNodes().get(0).getId());
        assertEquals("service", graph.getNodes().get(0).getGroup());
        assertEquals(1, graph.getEdges().size());
        assertEquals("com.x.C1", graph.getEdges().get(0).getSource());
        assertEquals("com.x.C2", graph.getEdges().get(0).getTarget());
        assertEquals("dependency", graph.getEdges().get(0).getType());

        ArgumentCaptor<String> queryCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Value> valueCaptor = ArgumentCaptor.forClass(Value.class);
        verify(session, times(3)).run(queryCaptor.capture(), valueCaptor.capture());
        assertEquals("MATCH (c:Class {projectId: $projectId}) RETURN count(c) AS total",
                queryCaptor.getAllValues().get(0));
        assertEquals("MATCH (c:Class {projectId: $projectId}) RETURN c ORDER BY c.fqn SKIP $skip LIMIT $limit",
                queryCaptor.getAllValues().get(1));
        Map<String, Object> pageParams = valueCaptor.getAllValues().get(1).asMap();
        assertEquals(PROJECT_ID, pageParams.get("projectId"));
        assertEquals(0L, pageParams.get("skip"));
        assertEquals(2L, pageParams.get("limit"));
        Map<String, Object> edgeParams = valueCaptor.getAllValues().get(2).asMap();
        assertEquals(Arrays.asList("com.x.C1", "com.x.C2"), edgeParams.get("pageFqns"));
    }

    @Test
    @DisplayName("分页 CLASS 级空结果 + page=0 越界钳制：skip 归零、无边查询、hasMore=false")
    void queryFullGraphPaged_classLevelEmptyPage_shouldClampSkipAndSkipEdges() {
        when(neo4jDriver.session()).thenReturn(session);
        when(session.run(anyString(), any(Value.class))).thenAnswer(invocation -> {
            String query = invocation.getArgument(0);
            if (query.contains("count(c) AS total")) {
                return emptyResult();
            }
            return emptyResult();
        });

        GraphVO graph = service.queryFullGraphPaged(PROJECT_ID, "CLASS", 0, 10);

        assertEquals(0L, graph.getTotalNodes());
        assertEquals(0, graph.getPage());
        assertEquals(10, graph.getSize());
        assertEquals(Boolean.FALSE, graph.getHasMore());
        assertTrue(graph.getNodes().isEmpty());
        assertTrue(graph.getEdges().isEmpty());

        ArgumentCaptor<Value> valueCaptor = ArgumentCaptor.forClass(Value.class);
        verify(session, times(2)).run(anyString(), valueCaptor.capture());
        assertEquals(0L, valueCaptor.getAllValues().get(1).asMap().get("skip"));
    }

    @Test
    @DisplayName("分页 PACKAGE 级（小写 level 归一化）：按包聚合 + 页内边过滤")
    void queryFullGraphPaged_packageLevel_shouldAggregatePackages() {
        when(neo4jDriver.session()).thenReturn(session);
        when(session.run(anyString(), any(Value.class))).thenAnswer(invocation -> {
            String query = invocation.getArgument(0);
            if (query.contains("classCount")) {
                Map<String, Value> pkgA = new HashMap<>();
                pkgA.put("packageName", stringValue("com.a"));
                pkgA.put("layer", stringValue("service"));
                pkgA.put("classCount", intValue(2));
                pkgA.put("totalMethods", intValue(10));
                pkgA.put("totalLineCount", intValue(100));
                Map<String, Value> pkgB = new HashMap<>();
                pkgB.put("packageName", stringValue("com.b"));
                pkgB.put("layer", stringValue(null));
                pkgB.put("classCount", intValue(1));
                pkgB.put("totalMethods", intValue(4));
                pkgB.put("totalLineCount", intValue(30));
                return resultOf(recordOf(pkgA), recordOf(pkgB));
            }
            Map<String, Value> edge1 = new HashMap<>();
            edge1.put("srcPkg", stringValue("com.a"));
            edge1.put("tgtPkg", stringValue("com.b"));
            Map<String, Value> edgeSame = new HashMap<>();
            edgeSame.put("srcPkg", stringValue("com.b"));
            edgeSame.put("tgtPkg", stringValue("com.b"));
            Map<String, Value> edgeOut = new HashMap<>();
            edgeOut.put("srcPkg", stringValue("com.a"));
            edgeOut.put("tgtPkg", stringValue("com.c"));
            return resultOf(recordOf(edge1), recordOf(edgeSame), recordOf(edgeOut));
        });

        GraphVO graph = service.queryFullGraphPaged(PROJECT_ID, "package", 1, 10);

        assertEquals(2L, graph.getTotalNodes());
        assertEquals(Boolean.FALSE, graph.getHasMore());
        assertEquals(2, graph.getNodes().size());
        GraphVO.NodeVO voA = graph.getNodes().get(0);
        assertEquals("com.a", voA.getId());
        assertEquals("com.a", voA.getLabel());
        assertEquals("service", voA.getGroup());
        assertEquals("service", voA.getLayer());
        assertEquals(10, voA.getMethods());
        assertEquals(100, voA.getLineCount());
        assertNull(voA.getClassType());
        GraphVO.NodeVO voB = graph.getNodes().get(1);
        assertEquals("com.b", voB.getId());
        assertEquals("unknown", voB.getGroup());
        assertNull(voB.getLayer());

        assertEquals(1, graph.getEdges().size());
        assertEquals("com.a", graph.getEdges().get(0).getSource());
        assertEquals("com.b", graph.getEdges().get(0).getTarget());
        assertEquals("dependency", graph.getEdges().get(0).getType());

        ArgumentCaptor<String> queryCaptor = ArgumentCaptor.forClass(String.class);
        verify(session, times(2)).run(queryCaptor.capture(), any(Value.class));
        assertEquals("MATCH (c:Class {projectId: $projectId}) RETURN c.packageName AS packageName, "
                + "head(collect(c.layer)) AS layer, count(c) AS classCount, sum(c.totalMethods) AS totalMethods, "
                + "sum(c.lineCount) AS totalLineCount ORDER BY packageName",
                normalize(queryCaptor.getAllValues().get(0)));
        assertEquals("MATCH (a:Class {projectId: $projectId})-[r:DEPENDS_ON]->(b:Class {projectId: $projectId}) "
                + "WHERE a.packageName <> b.packageName RETURN DISTINCT a.packageName AS srcPkg, b.packageName AS tgtPkg",
                normalize(queryCaptor.getAllValues().get(1)));
    }

    @Test
    @DisplayName("分页 MODULE 级：同模块包合并、layer 回填、模块内边剔除、跨页边剔除")
    void queryFullGraphPaged_moduleLevel_shouldMergePackages() {
        when(neo4jDriver.session()).thenReturn(session);
        when(session.run(anyString(), any(Value.class))).thenAnswer(invocation -> {
            String query = invocation.getArgument(0);
            if (query.contains("classCount")) {
                Map<String, Value> pkgA = new HashMap<>();
                pkgA.put("packageName", stringValue("com.codeatlas.a"));
                pkgA.put("layer", stringValue(null));
                pkgA.put("classCount", intValue(1));
                pkgA.put("totalMethods", intValue(3));
                pkgA.put("totalLineCount", intValue(5));
                Map<String, Value> pkgB = new HashMap<>();
                pkgB.put("packageName", stringValue("com.codeatlas.b"));
                pkgB.put("layer", stringValue("SERVICE"));
                pkgB.put("classCount", intValue(2));
                pkgB.put("totalMethods", intValue(4));
                pkgB.put("totalLineCount", intValue(7));
                Map<String, Value> pkgC = new HashMap<>();
                pkgC.put("packageName", stringValue("com.codeatlas.c"));
                pkgC.put("layer", stringValue("OTHER"));
                pkgC.put("classCount", intValue(1));
                pkgC.put("totalMethods", intValue(2));
                pkgC.put("totalLineCount", intValue(3));
                Map<String, Value> pkgSingle = new HashMap<>();
                pkgSingle.put("packageName", stringValue("single"));
                pkgSingle.put("layer", stringValue("UTIL"));
                pkgSingle.put("classCount", intValue(1));
                pkgSingle.put("totalMethods", intValue(2));
                pkgSingle.put("totalLineCount", intValue(3));
                Map<String, Value> pkgNull = new HashMap<>();
                pkgNull.put("packageName", stringValue(null));
                pkgNull.put("layer", stringValue(null));
                pkgNull.put("classCount", intValue(1));
                pkgNull.put("totalMethods", intValue(0));
                pkgNull.put("totalLineCount", intValue(0));
                Map<String, Value> pkgEmpty = new HashMap<>();
                pkgEmpty.put("packageName", stringValue(""));
                pkgEmpty.put("layer", stringValue(null));
                pkgEmpty.put("classCount", intValue(1));
                pkgEmpty.put("totalMethods", intValue(1));
                pkgEmpty.put("totalLineCount", intValue(1));
                return resultOf(recordOf(pkgA), recordOf(pkgB), recordOf(pkgC), recordOf(pkgSingle),
                        recordOf(pkgNull), recordOf(pkgEmpty));
            }
            Map<String, Value> sameModule = new HashMap<>();
            sameModule.put("srcPkg", stringValue("com.codeatlas.a"));
            sameModule.put("tgtPkg", stringValue("com.codeatlas.b"));
            Map<String, Value> crossModule = new HashMap<>();
            crossModule.put("srcPkg", stringValue("com.codeatlas.a"));
            crossModule.put("tgtPkg", stringValue("single"));
            Map<String, Value> toOffPage = new HashMap<>();
            toOffPage.put("srcPkg", stringValue("single"));
            toOffPage.put("tgtPkg", stringValue(null));
            Map<String, Value> defaultSource = new HashMap<>();
            defaultSource.put("srcPkg", stringValue(null));
            defaultSource.put("tgtPkg", stringValue("com.codeatlas.a"));
            return resultOf(recordOf(sameModule), recordOf(crossModule),
                    recordOf(toOffPage), recordOf(defaultSource));
        });

        GraphVO graph = service.queryFullGraphPaged(PROJECT_ID, "MODULE", 1, 2);

        assertEquals(3L, graph.getTotalNodes());
        assertEquals(Boolean.TRUE, graph.getHasMore());
        assertEquals(2, graph.getNodes().size());
        GraphVO.NodeVO merged = graph.getNodes().get(0);
        assertEquals("com.codeatlas", merged.getId());
        assertEquals("com.codeatlas", merged.getLabel());
        assertEquals("service", merged.getGroup());
        assertEquals("SERVICE", merged.getLayer());
        assertEquals(9, merged.getMethods());
        assertEquals(15, merged.getLineCount());
        GraphVO.NodeVO single = graph.getNodes().get(1);
        assertEquals("single", single.getId());
        assertEquals("util", single.getGroup());

        assertEquals(1, graph.getEdges().size());
        assertEquals("com.codeatlas", graph.getEdges().get(0).getSource());
        assertEquals("single", graph.getEdges().get(0).getTarget());
    }

    @Test
    @DisplayName("分页 PACKAGE 级页码超界：返回空页但保留总数与分页元数据")
    void queryFullGraphPaged_pageBeyondEnd_shouldReturnEmptyPage() {
        when(neo4jDriver.session()).thenReturn(session);
        when(session.run(anyString(), any(Value.class))).thenAnswer(invocation -> {
            String query = invocation.getArgument(0);
            if (query.contains("classCount")) {
                Map<String, Value> pkg = new HashMap<>();
                pkg.put("packageName", stringValue("com.a"));
                pkg.put("layer", stringValue("service"));
                pkg.put("classCount", intValue(1));
                pkg.put("totalMethods", intValue(1));
                pkg.put("totalLineCount", intValue(1));
                return resultOf(recordOf(pkg));
            }
            Map<String, Value> same = new HashMap<>();
            same.put("srcPkg", stringValue("com.a"));
            same.put("tgtPkg", stringValue("com.a"));
            return resultOf(recordOf(same));
        });

        GraphVO graph = service.queryFullGraphPaged(PROJECT_ID, "PACKAGE", 3, 10);

        assertEquals(1L, graph.getTotalNodes());
        assertEquals(3, graph.getPage());
        assertEquals(10, graph.getSize());
        assertEquals(Boolean.FALSE, graph.getHasMore());
        assertTrue(graph.getNodes().isEmpty());
        assertTrue(graph.getEdges().isEmpty());
    }

    @Test
    @DisplayName("分页 CLASS 级查询异常：返回空图兜底")
    void queryFullGraphPaged_classLevelFails_shouldReturnEmptyGraph() {
        when(neo4jDriver.session()).thenThrow(new RuntimeException("neo4j down"));

        GraphVO graph = service.queryFullGraphPaged(PROJECT_ID, "CLASS", 1, 10);

        assertTrue(graph.getNodes().isEmpty());
        assertTrue(graph.getEdges().isEmpty());
        assertNull(graph.getTotalNodes());
    }

    @Test
    @DisplayName("分页 MODULE 级查询异常：返回空图兜底")
    void queryFullGraphPaged_moduleLevelFails_shouldReturnEmptyGraph() {
        when(neo4jDriver.session()).thenThrow(new RuntimeException("neo4j down"));

        GraphVO graph = service.queryFullGraphPaged(PROJECT_ID, "MODULE", 1, 10);

        assertTrue(graph.getNodes().isEmpty());
        assertTrue(graph.getEdges().isEmpty());
    }

    // ==================== queryDirectNeighbors ====================

    @Test
    @DisplayName("出向邻居：依赖列表 + 继承/实现列表格式化输出，缺失 layer 用 unknown")
    void queryDirectNeighbors_outgoing_shouldFormatOutput() {
        when(neo4jDriver.session()).thenReturn(session);
        when(session.run(anyString(), any(Value.class))).thenAnswer(invocation -> {
            String query = invocation.getArgument(0);
            if (query.contains("EXTENDS|IMPLEMENTS")) {
                Map<String, Value> x = new HashMap<>();
                x.put("fqn", stringValue("com.x.X"));
                x.put("name", stringValue("X"));
                x.put("rel", stringValue("EXTENDS"));
                Map<String, Value> y = new HashMap<>();
                y.put("fqn", stringValue("com.x.Y"));
                y.put("name", stringValue("Y"));
                y.put("rel", stringValue("IMPLEMENTS"));
                return resultOf(recordOf(x), recordOf(y));
            }
            Map<String, Value> b = new HashMap<>();
            b.put("fqn", stringValue("com.x.B"));
            b.put("name", stringValue("B"));
            b.put("layer", stringWithDefaultValue("SERVICE"));
            Map<String, Value> c = new HashMap<>();
            c.put("fqn", stringValue("com.x.C"));
            c.put("name", stringValue("C"));
            c.put("layer", stringWithDefaultValue("unknown"));
            return resultOf(recordOf(b), recordOf(c));
        });

        String output = service.queryDirectNeighbors(9L, "com.x.A", "outgoing");

        assertEquals("出向依赖（com.x.A 依赖以下类）:\n"
                + "- B (com.x.B) [SERVICE]\n"
                + "- C (com.x.C) [unknown]\n"
                + "继承/实现（com.x.A 继承或实现的类型）:\n"
                + "- X (com.x.X) [继承]\n"
                + "- Y (com.x.Y) [实现]\n", output);

        ArgumentCaptor<String> queryCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Value> valueCaptor = ArgumentCaptor.forClass(Value.class);
        verify(session, times(2)).run(queryCaptor.capture(), valueCaptor.capture());
        assertEquals("MATCH (a:Class {fqn: $fqn})-[r:DEPENDS_ON]->(b:Class) WHERE b.projectId = $projectId "
                        + "RETURN b.fqn AS fqn, b.simpleName AS name, b.layer AS layer ORDER BY name LIMIT 30",
                normalize(queryCaptor.getAllValues().get(0)));
        assertEquals("MATCH (a:Class {fqn: $fqn})-[r:EXTENDS|IMPLEMENTS]->(b:Class) WHERE b.projectId = $projectId "
                        + "RETURN b.fqn AS fqn, b.simpleName AS name, type(r) AS rel ORDER BY name LIMIT 20",
                normalize(queryCaptor.getAllValues().get(1)));
        Map<String, Object> params = valueCaptor.getAllValues().get(0).asMap();
        assertEquals("com.x.A", params.get("fqn"));
        assertEquals(9L, params.get("projectId"));
    }

    @Test
    @DisplayName("出向邻居全为空：两个小节均输出 (无)")
    void queryDirectNeighbors_outgoingEmpty_shouldPrintNone() {
        when(neo4jDriver.session()).thenReturn(session);
        Result noResult = emptyResult();
        when(session.run(anyString(), any(Value.class))).thenReturn(noResult);

        String output = service.queryDirectNeighbors(9L, "com.x.A", "outgoing");

        assertEquals("出向依赖（com.x.A 依赖以下类）:\n(无)\n"
                + "继承/实现（com.x.A 继承或实现的类型）:\n(无)\n", output);
    }

    @Test
    @DisplayName("入向邻居：仅查询反向依赖与反向继承，格式化输出")
    void queryDirectNeighbors_incoming_shouldFormatOutput() {
        when(neo4jDriver.session()).thenReturn(session);
        when(session.run(anyString(), any(Value.class))).thenAnswer(invocation -> {
            String query = invocation.getArgument(0);
            if (query.contains("EXTENDS|IMPLEMENTS")) {
                Map<String, Value> h = new HashMap<>();
                h.put("fqn", stringValue("com.x.H"));
                h.put("name", stringValue("H"));
                h.put("rel", stringValue("EXTENDS"));
                Map<String, Value> i = new HashMap<>();
                i.put("fqn", stringValue("com.x.I"));
                i.put("name", stringValue("I"));
                i.put("rel", stringValue("IMPLEMENTS"));
                return resultOf(recordOf(h), recordOf(i));
            }
            Map<String, Value> p = new HashMap<>();
            p.put("fqn", stringValue("com.x.P"));
            p.put("name", stringValue("P"));
            p.put("layer", stringWithDefaultValue("CONTROLLER"));
            return resultOf(recordOf(p));
        });

        String output = service.queryDirectNeighbors(9L, "com.x.A", "incoming");

        assertEquals("入向依赖（以下类依赖 com.x.A）:\n"
                + "- P (com.x.P) [CONTROLLER]\n"
                + "被继承/被实现（以下类继承或实现了 com.x.A）:\n"
                + "- H (com.x.H) [继承]\n"
                + "- I (com.x.I) [实现]\n", output);

        ArgumentCaptor<String> queryCaptor = ArgumentCaptor.forClass(String.class);
        verify(session, times(2)).run(queryCaptor.capture(), any(Value.class));
        assertEquals("MATCH (a:Class)-[r:DEPENDS_ON]->(b:Class {fqn: $fqn}) WHERE a.projectId = $projectId "
                        + "RETURN a.fqn AS fqn, a.simpleName AS name, a.layer AS layer ORDER BY name LIMIT 30",
                normalize(queryCaptor.getAllValues().get(0)));
        assertEquals("MATCH (a:Class)-[r:EXTENDS|IMPLEMENTS]->(b:Class {fqn: $fqn}) WHERE a.projectId = $projectId "
                        + "RETURN a.fqn AS fqn, a.simpleName AS name, type(r) AS rel ORDER BY name LIMIT 20",
                normalize(queryCaptor.getAllValues().get(1)));
    }

    @Test
    @DisplayName("双向邻居：单向 direction 不触发查询，both 触发全部四个查询")
    void queryDirectNeighbors_both_shouldQueryAllDirections() {
        when(neo4jDriver.session()).thenReturn(session);
        Result noResult = emptyResult();
        when(session.run(anyString(), any(Value.class))).thenReturn(noResult);

        String output = service.queryDirectNeighbors(9L, "com.x.A", "both");

        assertEquals("出向依赖（com.x.A 依赖以下类）:\n(无)\n"
                + "继承/实现（com.x.A 继承或实现的类型）:\n(无)\n"
                + "入向依赖（以下类依赖 com.x.A）:\n(无)\n"
                + "被继承/被实现（以下类继承或实现了 com.x.A）:\n(无)\n", output);
        verify(session, times(4)).run(anyString(), any(Value.class));
    }

    @Test
    @DisplayName("未知方向或 null 方向：不执行任何查询，返回空串")
    void queryDirectNeighbors_unknownDirection_shouldReturnEmpty() {
        when(neo4jDriver.session()).thenReturn(session);

        assertEquals("", service.queryDirectNeighbors(9L, "com.x.A", "sideways"));
        assertEquals("", service.queryDirectNeighbors(9L, "com.x.A", null));

        verify(session, never()).run(anyString(), any(Value.class));
        verify(session, times(2)).close();
    }

    @Test
    @DisplayName("邻居查询驱动异常：返回带错误信息的降级文案")
    void queryDirectNeighbors_driverFails_shouldReturnErrorString() {
        when(neo4jDriver.session()).thenThrow(new RuntimeException("neo4j down"));

        String output = service.queryDirectNeighbors(9L, "com.x.A", "outgoing");

        assertEquals("(错误) Neo4j 查询失败: neo4j down", output);
    }

    // ==================== helpers ====================

    private static String normalize(String cypher) {
        return cypher.replaceAll("\\s+", " ").trim();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> paramList(Value value, String key) {
        return (List<Map<String, Object>>) value.asMap().get(key);
    }

    private ClassSummaryResult classResult(String fqn, String simpleName, String packageName,
                                           String layer, String classType,
                                           int publicMethods, int totalMethods, int lineCount) {
        ClassSummaryResult cls = new ClassSummaryResult();
        cls.setFqn(fqn);
        cls.setSimpleName(simpleName);
        cls.setPackageName(packageName);
        cls.setLayer(layer);
        cls.setClassType(classType);
        cls.setPublicMethods(publicMethods);
        cls.setTotalMethods(totalMethods);
        cls.setLineCount(lineCount);
        return cls;
    }

    private Result emptyResult() {
        Result result = mock(Result.class);
        lenient().when(result.hasNext()).thenReturn(false);
        return result;
    }

    private Result resultOf(Record... records) {
        Result result = mock(Result.class);
        Deque<Record> deque = new ArrayDeque<>(Arrays.asList(records));
        lenient().when(result.hasNext()).thenAnswer(invocation -> !deque.isEmpty());
        lenient().when(result.next()).thenAnswer(invocation -> deque.removeFirst());
        return result;
    }

    private Record recordOf(Map<String, Value> values) {
        Record record = mock(Record.class);
        lenient().when(record.get(anyString())).thenAnswer(invocation -> values.get(invocation.getArgument(0)));
        return record;
    }

    private Value stringValue(String value) {
        Value v = mock(Value.class);
        lenient().when(v.asString()).thenReturn(value);
        lenient().when(v.asString(anyString())).thenReturn(value);
        return v;
    }

    private Value stringWithDefaultValue(String value) {
        Value v = mock(Value.class);
        lenient().when(v.asString(anyString())).thenReturn(value);
        return v;
    }

    private Value longValue(long value) {
        Value v = mock(Value.class);
        lenient().when(v.asLong()).thenReturn(value);
        return v;
    }

    private Value intValue(int value) {
        Value v = mock(Value.class);
        lenient().when(v.asInt()).thenReturn(value);
        lenient().when(v.asInt(anyInt())).thenReturn(value);
        return v;
    }

    private Value listValue(List<Object> value) {
        Value v = mock(Value.class);
        lenient().when(v.asList()).thenReturn(value);
        return v;
    }

    private Value nodeValue(Node node) {
        Value v = mock(Value.class);
        lenient().when(v.asNode()).thenReturn(node);
        return v;
    }

    private Node fullNode(String fqn, String simpleName, String layer, String classType,
                          int totalMethods, int lineCount) {
        Map<String, Value> props = new LinkedHashMap<>();
        props.put("fqn", stringValue(fqn));
        if (simpleName != null) {
            props.put("simpleName", stringValue(simpleName));
        }
        if (layer != null) {
            props.put("layer", stringValue(layer));
        }
        if (classType != null) {
            props.put("classType", stringValue(classType));
        }
        props.put("totalMethods", intValue(totalMethods));
        props.put("lineCount", intValue(lineCount));
        return nodeWithProps(props);
    }

    private Node nodeWithProps(Map<String, Value> props) {
        Node node = mock(Node.class);
        lenient().when(node.containsKey(anyString()))
                .thenAnswer(invocation -> props.containsKey(invocation.getArgument(0)));
        lenient().when(node.get(anyString()))
                .thenAnswer(invocation -> props.get(invocation.getArgument(0)));
        return node;
    }
}
