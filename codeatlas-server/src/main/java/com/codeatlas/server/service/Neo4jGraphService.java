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
     * 批量导入扫描产生的类节点和依赖关系到 Neo4j。使用 UNWIND 批量操作，避免 N+1 问题。
     */
    public void importGraph(Long projectId, List<ClassSummaryResult> classes) {
        try (Session session = neo4jDriver.session()) {
            // 1. 删除旧数据
            session.run("MATCH (c:Class {projectId: $projectId}) DETACH DELETE c",
                    Values.parameters("projectId", projectId));

            if (classes.isEmpty()) {
                return;
            }

            // 2. 使用 UNWIND 批量创建类节点（单次 Cypher 调用）
            List<Map<String, Object>> nodeParams = new ArrayList<>();
            for (ClassSummaryResult cls : classes) {
                Map<String, Object> props = new HashMap<>();
                props.put("fqn", cls.getFqn());
                props.put("projectId", projectId);
                props.put("simpleName", cls.getSimpleName());
                props.put("packageName", cls.getPackageName());
                props.put("layer", cls.getLayer());
                props.put("classType", cls.getClassType());
                props.put("publicMethods", cls.getPublicMethods());
                props.put("totalMethods", cls.getTotalMethods());
                props.put("lineCount", cls.getLineCount());
                nodeParams.add(props);
            }
            session.run("""
                            UNWIND $nodes AS node
                            MERGE (c:Class {fqn: node.fqn})
                            SET c.projectId = node.projectId,
                                c.simpleName = node.simpleName,
                                c.packageName = node.packageName,
                                c.layer = node.layer,
                                c.classType = node.classType,
                                c.publicMethods = node.publicMethods,
                                c.totalMethods = node.totalMethods,
                                c.lineCount = node.lineCount
                            """,
                    Values.parameters("nodes", nodeParams));
            log.info("Neo4j: {} class nodes created for projectId={}", classes.size(), projectId);

            // 3. 使用 UNWIND 批量创建 DEPENDS_ON 关系（单次 Cypher 调用）
            Set<String> fqnSet = new HashSet<>();
            for (ClassSummaryResult cls : classes) {
                fqnSet.add(cls.getFqn());
            }

            List<Map<String, Object>> edgeParams = new ArrayList<>();
            for (ClassSummaryResult cls : classes) {
                for (String dep : cls.getDependencies()) {
                    if (dep != null && fqnSet.contains(dep)) {
                        Map<String, Object> edge = new HashMap<>();
                        edge.put("source", cls.getFqn());
                        edge.put("target", dep);
                        edgeParams.add(edge);
                    }
                }
            }

            if (!edgeParams.isEmpty()) {
                session.run("""
                                UNWIND $edges AS edge
                                MATCH (a:Class {fqn: edge.source})
                                MATCH (b:Class {fqn: edge.target})
                                MERGE (a)-[:DEPENDS_ON]->(b)
                                """,
                        Values.parameters("edges", edgeParams));
            }
            log.info("Neo4j: {} DEPENDS_ON edges created for projectId={}", edgeParams.size(), projectId);
        } catch (Exception e) {
            log.error("Neo4j import failed for projectId={}: {}", projectId, e.getMessage());
        }
    }

    /**
     * 从 Neo4j 查询项目依赖图谱。单次 Cypher 查询同时返回节点和边，减少网络往返。
     */
    public GraphVO queryFullGraph(Long projectId) {
        try (Session session = neo4jDriver.session()) {
            // 单次查询：先收集所有节点，再 OPTIONAL MATCH 边，避免无边时返回空
            var result = session.run(
                    "MATCH (c:Class {projectId: $projectId}) " +
                    "WITH collect(c) AS nodes " +
                    "OPTIONAL MATCH (a:Class {projectId: $projectId})-[r:DEPENDS_ON]->(b:Class {projectId: $projectId}) " +
                    "RETURN nodes, collect({source: a.fqn, target: b.fqn}) AS edges",
                    Values.parameters("projectId", projectId));

            List<GraphVO.NodeVO> graphNodes = new ArrayList<>();
            List<GraphVO.EdgeVO> graphEdges = new ArrayList<>();

            if (result.hasNext()) {
                var record = result.next();

                // 解析节点
                var nodesList = record.get("nodes").asList();
                for (var nodeValue : nodesList) {
                    var node = (org.neo4j.driver.types.Node) nodeValue;
                    String fqn = node.get("fqn").asString();

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

                // 解析边
                var edgesList = record.get("edges").asList();
                for (var edgeValue : edgesList) {
                    @SuppressWarnings("unchecked")
                    var edgeMap = (Map<String, Object>) edgeValue;
                    String source = (String) edgeMap.get("source");
                    String target = (String) edgeMap.get("target");
                    if (source != null && target != null) {
                        GraphVO.EdgeVO e = new GraphVO.EdgeVO();
                        e.setSource(source);
                        e.setTarget(target);
                        e.setType("dependency");
                        graphEdges.add(e);
                    }
                }
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

    /**
     * 带缩放级别过滤和分页的图谱查询。用于大项目按需加载。
     *
     * @param level 缩放级别: CLASS（所有节点）/ PACKAGE（仅包级别）/ MODULE（仅模块级别）
     * @param page  页码，从 1 开始
     * @param size  每页节点数
     */
    public GraphVO queryFullGraphPaged(Long projectId, String level, int page, int size) {
        String lvl = level != null ? level.toUpperCase() : "CLASS";
        if ("PACKAGE".equals(lvl) || "MODULE".equals(lvl)) {
            return queryPackageLevelGraph(projectId, lvl, page, size);
        }
        return queryClassLevelGraph(projectId, page, size);
    }

    /**
     * CLASS 级别分页查询：直接返回类节点，按 fqn 排序。
     */
    private GraphVO queryClassLevelGraph(Long projectId, int page, int size) {
        try (Session session = neo4jDriver.session()) {
            var countResult = session.run(
                    "MATCH (c:Class {projectId: $projectId}) RETURN count(c) AS total",
                    Values.parameters("projectId", projectId));
            long totalNodes = countResult.hasNext() ? countResult.next().get("total").asLong() : 0;

            int skip = Math.max(0, (page - 1) * size);

            var nodeResult = session.run(
                    "MATCH (c:Class {projectId: $projectId}) RETURN c ORDER BY c.fqn SKIP $skip LIMIT $limit",
                    Values.parameters("projectId", projectId, "skip", skip, "limit", size));

            List<GraphVO.NodeVO> graphNodes = new ArrayList<>();
            List<String> pageFqns = new ArrayList<>();
            while (nodeResult.hasNext()) {
                var record = nodeResult.next();
                var node = record.get("c").asNode();
                String fqn = node.get("fqn").asString();
                pageFqns.add(fqn);

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

            List<GraphVO.EdgeVO> graphEdges = new ArrayList<>();
            if (!pageFqns.isEmpty()) {
                var edgeResult = session.run(
                        "MATCH (a:Class {projectId: $projectId})-[r:DEPENDS_ON]->(b:Class {projectId: $projectId}) "
                                + "WHERE a.fqn IN $pageFqns AND b.fqn IN $pageFqns "
                                + "RETURN a.fqn AS source, b.fqn AS target",
                        Values.parameters("projectId", projectId, "pageFqns", pageFqns));
                while (edgeResult.hasNext()) {
                    var record = edgeResult.next();
                    GraphVO.EdgeVO e = new GraphVO.EdgeVO();
                    e.setSource(record.get("source").asString());
                    e.setTarget(record.get("target").asString());
                    e.setType("dependency");
                    graphEdges.add(e);
                }
            }

            GraphVO graph = new GraphVO();
            graph.setNodes(graphNodes);
            graph.setEdges(graphEdges);
            graph.setTotalNodes(totalNodes);
            graph.setPage(page);
            graph.setSize(size);
            graph.setHasMore(skip + size < totalNodes);
            log.info("Neo4j graph queried paged(CLASS): projectId={}, page={}, nodes={}, total={}",
                    projectId, page, graphNodes.size(), totalNodes);
            return graph;
        } catch (Exception e) {
            log.error("Neo4j graph paged query failed: {}", e.getMessage());
            GraphVO empty = new GraphVO();
            empty.setNodes(Collections.emptyList());
            empty.setEdges(Collections.emptyList());
            return empty;
        }
    }

    /**
     * PACKAGE/MODULE 级别：按包名聚合节点，统计类数、方法数、行数，构建包间依赖边。
     * MODULE 级别取包名前两段作为模块标识（如 com.codeatlas）。
     */
    private GraphVO queryPackageLevelGraph(Long projectId, String level, int page, int size) {
        boolean isModule = "MODULE".equals(level);
        try (Session session = neo4jDriver.session()) {
            // 1. 按 packageName 聚合所有类节点
            var aggResult = session.run(
                    "MATCH (c:Class {projectId: $projectId}) "
                            + "RETURN c.packageName AS packageName, "
                            + "head(collect(c.layer)) AS layer, "
                            + "count(c) AS classCount, "
                            + "sum(c.totalMethods) AS totalMethods, "
                            + "sum(c.lineCount) AS totalLineCount "
                            + "ORDER BY packageName",
                    Values.parameters("projectId", projectId));

            // 2. 收集原始包信息，模块级别做二次合并
            List<PackageInfo> rawPackages = new ArrayList<>();
            while (aggResult.hasNext()) {
                var record = aggResult.next();
                PackageInfo pi = new PackageInfo();
                pi.packageName = record.get("packageName").asString();
                pi.layer = record.get("layer").asString();
                pi.classCount = record.get("classCount").asInt();
                pi.totalMethods = record.get("totalMethods").asInt(0);
                pi.totalLineCount = record.get("totalLineCount").asInt(0);
                rawPackages.add(pi);
            }

            // 3. MODULE 级别：合并同模块的包
            Map<String, PackageInfo> merged = new LinkedHashMap<>();
            for (PackageInfo pi : rawPackages) {
                String key = isModule ? extractModule(pi.packageName) : pi.packageName;
                PackageInfo existing = merged.get(key);
                if (existing == null) {
                    PackageInfo mergedPi = new PackageInfo();
                    mergedPi.packageName = key;
                    mergedPi.layer = pi.layer;
                    mergedPi.classCount = pi.classCount;
                    mergedPi.totalMethods = pi.totalMethods;
                    mergedPi.totalLineCount = pi.totalLineCount;
                    merged.put(key, mergedPi);
                } else {
                    existing.classCount += pi.classCount;
                    existing.totalMethods += pi.totalMethods;
                    existing.totalLineCount += pi.totalLineCount;
                    if (existing.layer == null && pi.layer != null) {
                        existing.layer = pi.layer;
                    }
                }
            }

            // 4. 查询包/模块间依赖边
            Map<String, Set<String>> groupEdges = new LinkedHashMap<>();
            var edgeResult = session.run(
                    "MATCH (a:Class {projectId: $projectId})-[r:DEPENDS_ON]->(b:Class {projectId: $projectId}) "
                            + "WHERE a.packageName <> b.packageName "
                            + "RETURN DISTINCT a.packageName AS srcPkg, b.packageName AS tgtPkg",
                    Values.parameters("projectId", projectId));
            while (edgeResult.hasNext()) {
                var record = edgeResult.next();
                String srcPkg = record.get("srcPkg").asString();
                String tgtPkg = record.get("tgtPkg").asString();
                String srcKey = isModule ? extractModule(srcPkg) : srcPkg;
                String tgtKey = isModule ? extractModule(tgtPkg) : tgtPkg;
                if (!srcKey.equals(tgtKey)) {
                    groupEdges.computeIfAbsent(srcKey, k -> new HashSet<>()).add(tgtKey);
                }
            }

            // 5. 构建节点列表并分页
            List<PackageInfo> allGroups = new ArrayList<>(merged.values());
            long totalNodes = allGroups.size();
            int skip = Math.max(0, (page - 1) * size);
            int end = Math.min(skip + size, allGroups.size());
            List<PackageInfo> pageGroups = allGroups.subList(Math.min(skip, allGroups.size()), end);

            List<GraphVO.NodeVO> graphNodes = new ArrayList<>();
            Set<String> pageKeys = new HashSet<>();
            for (PackageInfo pi : pageGroups) {
                pageKeys.add(pi.packageName);
                GraphVO.NodeVO n = new GraphVO.NodeVO();
                n.setId(pi.packageName);
                n.setLabel(pi.packageName);
                String layer = pi.layer != null ? pi.layer.toLowerCase() : "unknown";
                n.setGroup(layer);
                n.setLayer(pi.layer);
                n.setMethods(pi.totalMethods);
                n.setLineCount(pi.totalLineCount);
                graphNodes.add(n);
            }

            // 6. 过滤边：仅保留当前页内的包间边
            List<GraphVO.EdgeVO> graphEdges = new ArrayList<>();
            for (Map.Entry<String, Set<String>> entry : groupEdges.entrySet()) {
                if (!pageKeys.contains(entry.getKey())) continue;
                for (String target : entry.getValue()) {
                    if (pageKeys.contains(target)) {
                        GraphVO.EdgeVO e = new GraphVO.EdgeVO();
                        e.setSource(entry.getKey());
                        e.setTarget(target);
                        e.setType("dependency");
                        graphEdges.add(e);
                    }
                }
            }

            GraphVO graph = new GraphVO();
            graph.setNodes(graphNodes);
            graph.setEdges(graphEdges);
            graph.setTotalNodes(totalNodes);
            graph.setPage(page);
            graph.setSize(size);
            graph.setHasMore(skip + size < totalNodes);
            log.info("Neo4j graph queried paged({}): projectId={}, page={}, groups={}, edges={}, total={}",
                    level, projectId, page, graphNodes.size(), graphEdges.size(), totalNodes);
            return graph;
        } catch (Exception e) {
            log.error("Neo4j graph paged query failed: {}", e.getMessage());
            GraphVO empty = new GraphVO();
            empty.setNodes(Collections.emptyList());
            empty.setEdges(Collections.emptyList());
            return empty;
        }
    }

    /**
     * 查询指定类的直接依赖邻居，支持方向过滤。用于 Agentic Search 工具调用。
     *
     * @param direction "outgoing"（我依赖谁）/ "incoming"（谁依赖我）/ "both"（双向）
     * @return 格式化的邻居列表字符串
     */
    public String queryDirectNeighbors(Long projectId, String fqn, String direction) {
        try (Session session = neo4jDriver.session()) {
            StringBuilder sb = new StringBuilder();
            boolean outgoing = "outgoing".equals(direction) || "both".equals(direction);
            boolean incoming = "incoming".equals(direction) || "both".equals(direction);

            if (outgoing) {
                var result = session.run(
                        "MATCH (a:Class {fqn: $fqn})-[r:DEPENDS_ON]->(b:Class) " +
                        "WHERE b.projectId = $projectId " +
                        "RETURN b.fqn AS fqn, b.simpleName AS name, b.layer AS layer " +
                        "ORDER BY name LIMIT 30",
                        Values.parameters("fqn", fqn, "projectId", projectId));
                sb.append("出向依赖（").append(fqn).append(" 依赖以下类）:\n");
                int count = 0;
                while (result.hasNext()) {
                    var record = result.next();
                    sb.append("- ").append(record.get("name").asString())
                            .append(" (").append(record.get("fqn").asString()).append(")")
                            .append(" [").append(record.get("layer").asString("unknown")).append("]\n");
                    count++;
                }
                if (count == 0) sb.append("(无)\n");
            }

            if (incoming) {
                var result = session.run(
                        "MATCH (a:Class)-[r:DEPENDS_ON]->(b:Class {fqn: $fqn}) " +
                        "WHERE a.projectId = $projectId " +
                        "RETURN a.fqn AS fqn, a.simpleName AS name, a.layer AS layer " +
                        "ORDER BY name LIMIT 30",
                        Values.parameters("fqn", fqn, "projectId", projectId));
                sb.append("入向依赖（以下类依赖 ").append(fqn).append("）:\n");
                int count = 0;
                while (result.hasNext()) {
                    var record = result.next();
                    sb.append("- ").append(record.get("name").asString())
                            .append(" (").append(record.get("fqn").asString()).append(")")
                            .append(" [").append(record.get("layer").asString("unknown")).append("]\n");
                    count++;
                }
                if (count == 0) sb.append("(无)\n");
            }

            return sb.toString();
        } catch (Exception e) {
            log.error("Neo4j direct neighbors query failed: {}", e.getMessage());
            return "(错误) Neo4j 查询失败: " + e.getMessage();
        }
    }

    /** 取包名前两段作为模块标识，如 com.codeatlas.server.config → com.codeatlas */
    private String extractModule(String packageName) {
        if (packageName == null || packageName.isEmpty()) {
            return "default";
        }
        int dots = 0;
        for (int i = 0; i < packageName.length(); i++) {
            if (packageName.charAt(i) == '.') {
                dots++;
                if (dots == 2) {
                    return packageName.substring(0, i);
                }
            }
        }
        return packageName;
    }

    /** 聚合包信息（内部类） */
    private static class PackageInfo {
        String packageName;
        String layer;
        int classCount;
        int totalMethods;
        int totalLineCount;
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
