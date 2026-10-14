package com.codeatlas.server.dto.response;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

@DisplayName("GraphVO 单元测试（图节点/边/分页元数据）")
class GraphVOTest {

    // ========== NodeVO ==========

    @Test
    @DisplayName("NodeVO 新建实例：引用字段默认 null，计数字段默认 0")
    void nodeVO_newInstance_shouldHaveDefaults() {
        GraphVO.NodeVO node = new GraphVO.NodeVO();

        assertNull(node.getId());
        assertNull(node.getLabel());
        assertNull(node.getGroup());
        assertNull(node.getLayer());
        assertNull(node.getClassType());
        assertEquals(0, node.getMethods());
        assertEquals(0, node.getFields());
        assertEquals(0, node.getLineCount());
    }

    @Test
    @DisplayName("NodeVO setter/getter：全字段往返值一致（互不串位）")
    void nodeVO_accessors_shouldRoundTripDistinctValues() {
        GraphVO.NodeVO node = new GraphVO.NodeVO();

        node.setId("com.demo.OrderService");
        node.setLabel("OrderService");
        node.setGroup("service");
        node.setLayer("service");
        node.setClassType("class");
        node.setMethods(7);
        node.setFields(3);
        node.setLineCount(120);

        assertEquals("com.demo.OrderService", node.getId());
        assertEquals("OrderService", node.getLabel());
        assertEquals("service", node.getGroup());
        assertEquals("service", node.getLayer());
        assertEquals("class", node.getClassType());
        assertEquals(7, node.getMethods());
        assertEquals(3, node.getFields());
        assertEquals(120, node.getLineCount());
    }

    // ========== GraphVO 主体 ==========

    @Test
    @DisplayName("GraphVO 新建实例：全部字段默认 null")
    void graphVO_newInstance_shouldHaveAllFieldsNull() {
        GraphVO graph = new GraphVO();

        assertNull(graph.getNodes());
        assertNull(graph.getEdges());
        assertNull(graph.getTotalNodes());
        assertNull(graph.getPage());
        assertNull(graph.getSize());
        assertNull(graph.getHasMore());
    }

    @Test
    @DisplayName("GraphVO setter/getter：节点/边列表与分页元数据原值存取")
    void graphVO_accessors_shouldRoundTripDistinctValues() {
        GraphVO graph = new GraphVO();
        GraphVO.NodeVO node = new GraphVO.NodeVO();
        node.setId("A");
        GraphVO.EdgeVO edge = new GraphVO.EdgeVO();
        edge.setSource("A");
        List<GraphVO.NodeVO> nodes = Arrays.asList(node);
        List<GraphVO.EdgeVO> edges = Arrays.asList(edge);

        graph.setNodes(nodes);
        graph.setEdges(edges);
        graph.setTotalNodes(999L);
        graph.setPage(2);
        graph.setSize(50);
        graph.setHasMore(true);

        assertSame(nodes, graph.getNodes());
        assertSame(edges, graph.getEdges());
        assertEquals(1, graph.getNodes().size());
        assertEquals("A", graph.getNodes().get(0).getId());
        assertEquals(1, graph.getEdges().size());
        assertEquals("A", graph.getEdges().get(0).getSource());
        assertEquals(999L, graph.getTotalNodes());
        assertEquals(2, graph.getPage());
        assertEquals(50, graph.getSize());
        assertEquals(Boolean.TRUE, graph.getHasMore());
    }

    // ========== EdgeVO ==========

    @Test
    @DisplayName("EdgeVO setter/getter：source/target/type 原值存取")
    void edgeVO_accessors_shouldRoundTripDistinctValues() {
        GraphVO.EdgeVO edge = new GraphVO.EdgeVO();

        edge.setSource("com.demo.AService");
        edge.setTarget("com.demo.BService");
        edge.setType("depends");

        assertEquals("com.demo.AService", edge.getSource());
        assertEquals("com.demo.BService", edge.getTarget());
        assertEquals("depends", edge.getType());
    }
}
