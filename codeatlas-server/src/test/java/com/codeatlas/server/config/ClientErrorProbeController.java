package com.codeatlas.server.config;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 测试专用无依赖探针控制器：提供最小端点以触发
 * 参数类型转换失败、缺少必需参数两类客户端错误，供 GlobalExceptionHandlerTest 断言异常映射。
 */
@RestController
@RequestMapping("/api/v1/probe")
public class ClientErrorProbeController {

    @GetMapping("/types/{id}")
    public String typeMismatch(@PathVariable Long id) {
        return "ok";
    }

    @GetMapping("/params")
    public String requiredParam(@RequestParam String value) {
        return "ok";
    }
}
