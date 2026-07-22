package com.codeatlas.server.config;

import com.codeatlas.common.constant.ErrorCode;
import com.codeatlas.common.dto.ApiResponse;
import com.codeatlas.common.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException e) {
        log.warn("Business exception: code={}, message={}", e.getCode(), e.getMessage());
        ApiResponse<Void> response = ApiResponse.error(e.getCode(), e.getMessage());
        response.setTraceId(MDC.get("traceId"));
        return ResponseEntity.status(mapHttpStatus(e.getCode()))
                .body(response);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnknown(Exception e) {
        log.error("Unhandled exception: {}", e.getMessage(), e);
        ApiResponse<Void> response = ApiResponse.error(ErrorCode.INTERNAL_ERROR);
        response.setTraceId(MDC.get("traceId"));
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
    }

    private HttpStatus mapHttpStatus(int code) {
        if (code == ErrorCode.UNAUTHORIZED.getCode()) return HttpStatus.UNAUTHORIZED;
        if (code == ErrorCode.FORBIDDEN.getCode()) return HttpStatus.FORBIDDEN;
        if (code == ErrorCode.NOT_FOUND.getCode()) return HttpStatus.NOT_FOUND;
        if (code == ErrorCode.SERVICE_UNAVAILABLE.getCode()) return HttpStatus.SERVICE_UNAVAILABLE;
        if (code >= 40000) return HttpStatus.BAD_REQUEST;
        if (code >= 30000) return HttpStatus.BAD_REQUEST;
        if (code >= 20000) return HttpStatus.SERVICE_UNAVAILABLE;
        if (code >= 10000) return HttpStatus.BAD_REQUEST;
        return HttpStatus.OK;
    }
}
