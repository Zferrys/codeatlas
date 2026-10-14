package com.codeatlas.server.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.EnableScheduling;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("SchedulingConfig 定时任务开关配置测试")
class SchedulingConfigTest {

    @Test
    @DisplayName("配置类带 @EnableScheduling 注解以启用定时任务")
    void shouldEnableScheduling() {
        assertTrue(SchedulingConfig.class.isAnnotationPresent(EnableScheduling.class),
                "SchedulingConfig 必须标注 @EnableScheduling，否则 @Scheduled 任务不会执行");
    }

    @Test
    @DisplayName("配置类可实例化（空配置类，无额外依赖）")
    void shouldBeInstantiable() {
        assertNotNull(new SchedulingConfig());
    }
}
