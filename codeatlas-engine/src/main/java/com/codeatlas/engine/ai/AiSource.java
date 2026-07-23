package com.codeatlas.engine.ai;

/**
 * 源码引用 — 去幻觉的关键。
 * 每段 AI 输出必须附带具体源码位置，前端可点击验证。
 */
public class AiSource {

    private String className;    // 全限定类名
    private String methodName;   // 方法名（可选）
    private int lineNumber;      // 行号（可选，-1 表示无）
    private String snippet;      // 代码片段

    public AiSource() {
        this.lineNumber = -1;
    }

    public AiSource(String className) {
        this();
        this.className = className;
    }

    public String getClassName() { return className; }
    public void setClassName(String className) { this.className = className; }

    public String getMethodName() { return methodName; }
    public void setMethodName(String methodName) { this.methodName = methodName; }

    public int getLineNumber() { return lineNumber; }
    public void setLineNumber(int lineNumber) { this.lineNumber = lineNumber; }

    public String getSnippet() { return snippet; }
    public void setSnippet(String snippet) { this.snippet = snippet; }
}
