package com.codeatlas.common.constant;

/**
 * 统一错误码枚举，按业务域分段。
 */
public enum ErrorCode {

    // ==================== 通用错误 ====================
    SUCCESS(200, "成功"),
    BAD_REQUEST(400, "请求参数错误"),
    UNAUTHORIZED(401, "未登录或 Token 已过期"),
    FORBIDDEN(403, "无权限访问该资源"),
    NOT_FOUND(404, "资源不存在"),
    CONFLICT(409, "资源冲突"),
    INTERNAL_ERROR(500, "服务器内部错误"),
    SERVICE_UNAVAILABLE(503, "服务暂时不可用"),

    // ==================== 业务错误 (10001-19999) ====================
    PROJECT_NOT_FOUND(10001, "项目不存在"),
    PROJECT_ACCESS_DENIED(10002, "无权访问该项目"),
    SCAN_ALREADY_RUNNING(10003, "该项目的扫描已在运行中"),
    SCAN_NOT_FOUND(10004, "扫描记录不存在"),
    SOURCE_CLONE_FAILED(10005, "源码仓库克隆失败"),
    SOURCE_UNSUPPORTED_LANG(10006, "不支持该编程语言"),
    PARSE_FAILED(10007, "代码解析失败"),
    GRAPH_BUILD_FAILED(10008, "依赖图构建失败"),
    RULE_NOT_FOUND(10009, "宪法规则不存在"),
    RULE_REFERENCED(10010, "规则被违规记录引用，不可删除"),
    REPORT_GENERATE_FAILED(10011, "报告生成失败"),

    // ==================== AI 错误 (20001-29999) ====================
    AI_API_TIMEOUT(20001, "AI API 调用超时"),
    AI_API_RATE_LIMITED(20002, "AI API 频率限制"),
    AI_API_AUTH_FAILED(20003, "AI API 认证失败"),
    AI_API_INTERNAL_ERROR(20004, "AI API 内部错误"),
    AI_RESPONSE_PARSE_FAILED(20005, "AI 响应解析失败"),
    AI_CONTENT_FILTERED(20006, "AI 输出被内容过滤器拦截"),
    AI_MODEL_NOT_FOUND(20007, "模型不存在或未配置"),
    AI_HALLUCINATION_DETECTED(20008, "检测到 AI 幻觉，输出已被拦截"),
    AI_TOKEN_BUDGET_EXCEEDED(20009, "Token 月度预算已耗尽"),
    AI_CONTEXT_TOO_LARGE(20010, "项目过大，超出模型上下文限制"),

    // ==================== 文件错误 (30001-39999) ====================
    FILE_TOO_LARGE(30001, "文件大小超过限制"),
    FILE_TYPE_NOT_ALLOWED(30002, "不支持的文件类型"),
    FILE_UPLOAD_FAILED(30003, "文件上传失败"),

    // ==================== 用户错误 (40001-49999) ====================
    USERNAME_EXISTS(40001, "用户名已存在"),
    EMAIL_EXISTS(40002, "邮箱已注册"),
    PASSWORD_TOO_WEAK(40003, "密码强度不足"),
    USER_DISABLED(40004, "账户已被禁用"),
    LOGIN_FAILED(40005, "用户名或密码错误");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int getCode() { return code; }
    public String getMessage() { return message; }
}
