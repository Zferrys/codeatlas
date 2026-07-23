package com.codeatlas.server.entity;

import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;
import org.springframework.data.neo4j.core.schema.Property;

@Node("Class")
public class ClassNode {

    @Id
    private String fqn;

    @Property("projectId")
    private Long projectId;

    @Property("simpleName")
    private String simpleName;

    @Property("packageName")
    private String packageName;

    @Property("layer")
    private String layer;

    @Property("classType")
    private String classType;

    @Property("publicMethods")
    private Integer publicMethods;

    @Property("totalMethods")
    private Integer totalMethods;

    @Property("lineCount")
    private Integer lineCount;

    public ClassNode() {}

    public String getFqn() { return fqn; }
    public void setFqn(String fqn) { this.fqn = fqn; }
    public Long getProjectId() { return projectId; }
    public void setProjectId(Long projectId) { this.projectId = projectId; }
    public String getSimpleName() { return simpleName; }
    public void setSimpleName(String simpleName) { this.simpleName = simpleName; }
    public String getPackageName() { return packageName; }
    public void setPackageName(String packageName) { this.packageName = packageName; }
    public String getLayer() { return layer; }
    public void setLayer(String layer) { this.layer = layer; }
    public String getClassType() { return classType; }
    public void setClassType(String classType) { this.classType = classType; }
    public Integer getPublicMethods() { return publicMethods; }
    public void setPublicMethods(Integer publicMethods) { this.publicMethods = publicMethods; }
    public Integer getTotalMethods() { return totalMethods; }
    public void setTotalMethods(Integer totalMethods) { this.totalMethods = totalMethods; }
    public Integer getLineCount() { return lineCount; }
    public void setLineCount(Integer lineCount) { this.lineCount = lineCount; }
}
