package com.codeatlas.common.dto;

import com.codeatlas.common.constant.ErrorCode;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 统一 API 响应包装。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse<T> {

    private int code;
    private String message;
    private T data;
    private String traceId;
    private long timestamp;

    public ApiResponse() {
        this.timestamp = System.currentTimeMillis();
    }

    public static <T> ApiResponse<T> success(T data) {
        ApiResponse<T> r = new ApiResponse<>();
        r.code = ErrorCode.SUCCESS.getCode();
        r.message = "success";
        r.data = data;
        return r;
    }

    public static <T> ApiResponse<T> success() {
        return success(null);
    }

    public static <T> ApiResponse<T> error(int code, String message) {
        ApiResponse<T> r = new ApiResponse<>();
        r.code = code;
        r.message = message;
        return r;
    }

    public static <T> ApiResponse<T> error(ErrorCode errorCode) {
        return error(errorCode.getCode(), errorCode.getMessage());
    }

    public static <T> ApiResponse<T> error(ErrorCode errorCode, String detail) {
        return error(errorCode.getCode(), detail != null ? detail : errorCode.getMessage());
    }

    // ========== getters / setters ==========

    public int getCode() { return code; }
    public void setCode(int code) { this.code = code; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public T getData() { return data; }
    public void setData(T data) { this.data = data; }

    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }

    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
}
