package com.codeatlas.server.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@DisplayName("ClassNode 单元测试（Neo4j 节点全字段读写）")
class ClassNodeTest {

    @Test
    @DisplayName("显式无参构造：全部字段默认 null")
    void newInstance_shouldHaveAllFieldsNull() {
        ClassNode node = new ClassNode();

        assertNull(node.getFqn());
        assertNull(node.getProjectId());
        assertNull(node.getSimpleName());
        assertNull(node.getPackageName());
        assertNull(node.getLayer());
        assertNull(node.getClassType());
        assertNull(node.getPublicMethods());
        assertNull(node.getTotalMethods());
        assertNull(node.getLineCount());
    }

    @Test
    @DisplayName("setter/getter：全字段往返值一致（互不串位）")
    void accessors_shouldRoundTripDistinctValues() {
        ClassNode node = new ClassNode();

        node.setFqn("com.codeatlas.server.service.ProjectService");
        node.setProjectId(7L);
        node.setSimpleName("ProjectService");
        node.setPackageName("com.codeatlas.server.service");
        node.setLayer("service");
        node.setClassType("interface");
        node.setPublicMethods(11);
        node.setTotalMethods(23);
        node.setLineCount(456);

        assertEquals("com.codeatlas.server.service.ProjectService", node.getFqn());
        assertEquals(7L, node.getProjectId());
        assertEquals("ProjectService", node.getSimpleName());
        assertEquals("com.codeatlas.server.service", node.getPackageName());
        assertEquals("service", node.getLayer());
        assertEquals("interface", node.getClassType());
        assertEquals(11, node.getPublicMethods());
        assertEquals(23, node.getTotalMethods());
        assertEquals(456, node.getLineCount());
    }
}
