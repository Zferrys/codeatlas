package com.codeatlas.server.dto.response;

import java.util.List;

public class GraphVO {

    private List<NodeVO> nodes;
    private List<EdgeVO> edges;

    public List<NodeVO> getNodes() { return nodes; }
    public void setNodes(List<NodeVO> nodes) { this.nodes = nodes; }
    public List<EdgeVO> getEdges() { return edges; }
    public void setEdges(List<EdgeVO> edges) { this.edges = edges; }

    public static class NodeVO {
        private String id;
        private String label;
        private String group;
        private String layer;
        private int methods;
        private int fields;
        private int lineCount;

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getLabel() { return label; }
        public void setLabel(String label) { this.label = label; }
        public String getGroup() { return group; }
        public void setGroup(String group) { this.group = group; }
        public String getLayer() { return layer; }
        public void setLayer(String layer) { this.layer = layer; }
        public int getMethods() { return methods; }
        public void setMethods(int methods) { this.methods = methods; }
        public int getFields() { return fields; }
        public void setFields(int fields) { this.fields = fields; }
        public int getLineCount() { return lineCount; }
        public void setLineCount(int lineCount) { this.lineCount = lineCount; }
    }

    public static class EdgeVO {
        private String source;
        private String target;
        private String type;

        public String getSource() { return source; }
        public void setSource(String source) { this.source = source; }
        public String getTarget() { return target; }
        public void setTarget(String target) { this.target = target; }
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
    }
}
