package com.codeatlas.server.service;

import com.codeatlas.engine.parser.ClassSummaryResult;
import com.codeatlas.server.dto.response.GraphVO;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.neo4j.driver.Values;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class Neo4jGraphService {

    private static final Logger log = LoggerFactory.getLogger(Neo4jGraphService.class);

    private final Driver neo4jDriver;

    public Neo4jGraphService(Driver neo4jDriver) {
        this.neo4jDriver = neo4jDriver;
    }

    /**
     * 批量导入扫描产生的类节点和依赖关系到 Neo4j。
     */
    public void importGraph(Long projectId, List<ClassSummaryResult> classes) {
        try (Session session = neo4jDriver.session()) {
            // 1. 删除旧数据
            session.run("MATCH (c:Class {projectId: $projectId}) DETACH DELETE c",
                    Values.parameters("projectId", projectId));

            // 2. 批量创建类节点
            for (ClassSummaryResult cls : classes) {
                session.run("""
                                MERGE (c:Class {fqn: $fqn})
                                SET c.projectId = $projectId,
                                    c.simpleName = $simpleName,
                                    c.packageName = $packageName,
                                    c.layer = $layer,
                                    c.classType = $classType,
                                    c.publicMethods = $publicMethods,
                                    c.totalMethods = $totalMethods,
                                    c.lineCount = $lineCount
                                """,
                        Values.parameters(
                                "fqn", cls.getFqn(),
                                "projectId", projectId,
                                "simpleName", cls.getSimpleName(),
                                "packageName", cls.getPackageName(),
                                "layer", cls.getLayer(),
                                "classType", cls.getClassType(),
                                "publicMethods", cls.getPublicMethods(),
                                "totalMethods", cls.getTotalMethods(),
                                "lineCount", cls.getLineCount()
                        ));
            }
            log.info("Neo4j: {} class nodes created for projectId={}", classes.size(), projectId);

            // 3. 批量创建 DEPENDS_ON 关系
            Set<String> fqnSet = new HashSet<>();
            for (ClassSummaryResult cls : classes) {
                fqnSet.add(cls.getFqn());
            }

            int edgeCount = 0;
            for (ClassSummaryResult cls : classes) {
                for (String dep : cls.getDependencies()) {
                    if (dep != null && fqnSet.contains(dep)) {
                        session.run("""
                                        MATCH (a:Class {fqn: $source})
                                        MATCH (b:Class {fqn: $target})
                                        MERGE (a)-[:DEPENDS_ON]->(b)
                                        """,
                                Values.parameters("source", cls.getFqn(), "target", dep));
                        edgeCount++;
                    }
                }
            }
            log.info("Neo4j: {} DEPENDS_ON edges created for projectId={}", edgeCount, projectId);
        } catch (Exception e) {
            log.error("Neo4j import failed for projectId={}: {}", projectId, e.getMessage());
        }
    }

    /**
     * 从 Neo4j 查询项目依赖图谱。
     */
    public GraphVO queryFullGraph(Long projectId) {
        try (Session session = neo4jDriver.session()) {
            // 查询所有类节点
            var nodeResult = session.run(
                    "MATCH (c:Class {projectId: $projectId}) RETURN c",
                    Values.parameters("projectId", projectId));

            List<GraphVO.NodeVO> graphNodes = new ArrayList<>();
            Set<String> fqnSet = new HashSet<>();
            while (nodeResult.hasNext()) {
                var record = nodeResult.next();
                var node = record.get("c").asNode();
                String fqn = node.get("fqn").asString();
                fqnSet.add(fqn);

                GraphVO.NodeVO n = new GraphVO.NodeVO();
                n.setId(fqn);
                n.setLabel(safeProp(node, "simpleName"));
                String layer = safeProp(node, "layer");
                n.setGroup(layer != null ? layer.toLowerCase() : "unknown");
                n.setLayer(layer);
                n.setMethods(safePropInt(node, "totalMethods"));
                n.setLineCount(safePropInt(node, "lineCount"));
                graphNodes.add(n);
            }

            // 查询所有 DEPENDS_ON 关系
            var edgeResult = session.run(
                    "MATCH (a:Class {projectId: $projectId})-[r:DEPENDS_ON]->(b:Class) WHERE b.projectId = $projectId RETURN a.fqn AS source, b.fqn AS target",
                    Values.parameters("projectId", projectId));

            List<GraphVO.EdgeVO> graphEdges = new ArrayList<>();
            while (edgeResult.hasNext()) {
                var record = edgeResult.next();
                GraphVO.EdgeVO e = new GraphVO.EdgeVO();
                e.setSource(record.get("source").asString());
                e.setTarget(record.get("target").asString());
                e.setType("dependency");
                graphEdges.add(e);
            }

            GraphVO graph = new GraphVO();
            graph.setNodes(graphNodes);
            graph.setEdges(graphEdges);
            log.info("Neo4j graph queried: projectId={}, nodes={}, edges={}",
                    projectId, graphNodes.size(), graphEdges.size());
            return graph;
        } catch (Exception e) {
            log.error("Neo4j graph query failed: {}", e.getMessage());
            GraphVO empty = new GraphVO();
            empty.setNodes(Collections.emptyList());
            empty.setEdges(Collections.emptyList());
            return empty;
        }
    }

    /**
     * 从指定类出发，遍历 depth 层依赖子图（BFS）。
     */
    public GraphVO querySubgraph(Long projectId, String fqn, int depth) {
        try (Session session = neo4jDriver.session()) {
            Set<String> visitedFqns = new HashSet<>();
            List<GraphVO.NodeVO> graphNodes = new ArrayList<>();
            List<GraphVO.EdgeVO> graphEdges = new ArrayList<>();

            Set<String> currentLevel = new HashSet<>();
            currentLevel.add(fqn);

            for (int d = 0; d <= depth && !currentLevel.isEmpty(); d++) {
                Set<String> nextLevel = new HashSet<>();
                for (String currentFqn : currentLevel) {
                    if (!visitedFqns.add(currentFqn)) continue;

                    // 查询当前节点
                    var nodeResult = session.run(
                            "MATCH (c:Class {fqn: $fqn, projectId: $projectId}) RETURN c",
                            Values.parameters("fqn", currentFqn, "projectId", projectId));
                    if (nodeResult.hasNext()) {
                        var node = nodeResult.next().get("c").asNode();
                        GraphVO.NodeVO n = new GraphVO.NodeVO();
                        n.setId(node.get("fqn").asString());
                        n.setLabel(safeProp(node, "simpleName"));
                        String layer = safeProp(node, "layer");
                        n.setGroup(layer != null ? layer.toLowerCase() : "unknown");
                        n.setLayer(layer);
                        n.setMethods(safePropInt(node, "totalMethods"));
                        n.setLineCount(safePropInt(node, "lineCount"));
                        graphNodes.add(n);
                    }

                    if (d < depth) {
                        // 查询出边和入边的相邻节点
                        var edgeResult = session.run("""
                                        MATCH (a:Class {fqn: $fqn})-[r:DEPENDS_ON]->(b:Class)
                                        WHERE b.projectId = $projectId
                                        RETURN b.fqn AS target
                                        UNION
                                        MATCH (a:Class)-[r:DEPENDS_ON]->(b:Class {fqn: $fqn})
                                        WHERE a.projectId = $projectId
                                        RETURN a.fqn AS target
                                        """,
                                Values.parameters("fqn", currentFqn, "projectId", projectId));

                        while (edgeResult.hasNext()) {
                            String target = edgeResult.next().get("target").asString();
                            if (!visitedFqns.contains(target)) {
                                GraphVO.EdgeVO e = new GraphVO.EdgeVO();
                                e.setSource(currentFqn);
                                e.setTarget(target);
                                e.setType("dependency");
                                graphEdges.add(e);
                            }
                            nextLevel.add(target);
                        }
                    }
                }
                currentLevel = nextLevel;
            }

            GraphVO graph = new GraphVO();
            graph.setNodes(graphNodes);
            graph.setEdges(graphEdges);
            log.info("Neo4j subgraph queried: fqn={}, depth={}, nodes={}, edges={}",
                    fqn, depth, graphNodes.size(), graphEdges.size());
            return graph;
        } catch (Exception e) {
            log.error("Neo4j subgraph query failed: {}", e.getMessage());
            GraphVO empty = new GraphVO();
            empty.setNodes(Collections.emptyList());
            empty.setEdges(Collections.emptyList());
            return empty;
        }
    }

    private String safeProp(org.neo4j.driver.types.Node node, String key) {
        try {
            return node.containsKey(key) ? node.get(key).asString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private int safePropInt(org.neo4j.driver.types.Node node, String key) {
        try {
            return node.containsKey(key) ? node.get(key).asInt() : 0;
        } catch (Exception e) {
            return 0;
        }
    }
}
