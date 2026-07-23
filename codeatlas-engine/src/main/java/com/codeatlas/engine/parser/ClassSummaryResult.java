package com.codeatlas.engine.parser;

import java.util.List;

public class ClassSummaryResult {

    private String fqn;
    private String simpleName;
    private String packageName;
    private String classType;
    private int lineCount;
    private int publicMethods;
    private int totalMethods;
    private int fieldCount;
    private List<String> annotations;
    private List<String> imports;
    private List<String> methodNames;
    private List<String> dependencies;
    private List<String> fieldTypes;
    private String layer;

    // ---- getters ----

    public String getFqn() { return fqn; }
    public void setFqn(String fqn) { this.fqn = fqn; }
    public String getSimpleName() { return simpleName; }
    public void setSimpleName(String simpleName) { this.simpleName = simpleName; }
    public String getPackageName() { return packageName; }
    public void setPackageName(String packageName) { this.packageName = packageName; }
    public String getClassType() { return classType; }
    public void setClassType(String classType) { this.classType = classType; }
    public int getLineCount() { return lineCount; }
    public void setLineCount(int lineCount) { this.lineCount = lineCount; }
    public int getPublicMethods() { return publicMethods; }
    public void setPublicMethods(int publicMethods) { this.publicMethods = publicMethods; }
    public int getTotalMethods() { return totalMethods; }
    public void setTotalMethods(int totalMethods) { this.totalMethods = totalMethods; }
    public int getFieldCount() { return fieldCount; }
    public void setFieldCount(int fieldCount) { this.fieldCount = fieldCount; }
    public List<String> getAnnotations() { return annotations; }
    public void setAnnotations(List<String> annotations) { this.annotations = annotations; }
    public List<String> getImports() { return imports; }
    public void setImports(List<String> imports) { this.imports = imports; }
    public List<String> getMethodNames() { return methodNames; }
    public void setMethodNames(List<String> methodNames) { this.methodNames = methodNames; }
    public List<String> getDependencies() { return dependencies; }
    public void setDependencies(List<String> dependencies) { this.dependencies = dependencies; }
    public List<String> getFieldTypes() { return fieldTypes; }
    public void setFieldTypes(List<String> fieldTypes) { this.fieldTypes = fieldTypes; }
    public String getLayer() { return layer; }
    public void setLayer(String layer) { this.layer = layer; }
}
