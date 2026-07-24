package com.codeatlas.common.exception;

import com.codeatlas.common.constant.ErrorCode;

/**
 * AI 服务异常：模型不可用、熔断、幻觉等场景。
 * 前端可根据 errorCode 展示不同的降级提示。
 */
public class AiException extends RuntimeException {

    private final int code;
    private final boolean degraded;

    public AiException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.code = errorCode.getCode();
        this.degraded = false;
    }

    public AiException(ErrorCode errorCode, String detail) {
        super(detail != null ? detail : errorCode.getMessage());
        this.code = errorCode.getCode();
        this.degraded = false;
    }

    public AiException(ErrorCode errorCode, String detail, boolean degraded) {
        super(detail != null ? detail : errorCode.getMessage());
        this.code = errorCode.getCode();
        this.degraded = degraded;
    }

    public int getCode() { return code; }

    /** 是否为降级模式（部分功能不可用但核心功能正常） */
    public boolean isDegraded() { return degraded; }
}
