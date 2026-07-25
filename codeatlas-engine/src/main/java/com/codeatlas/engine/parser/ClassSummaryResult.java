package com.codeatlas.engine.parser;

import java.util.List;
import lombok.Data;

@Data
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

}
