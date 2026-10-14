package com.codeatlas.server.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("SwaggerConfig 单元测试（OpenAPI 元信息 + JWT 安全方案）")
class SwaggerConfigTest {

    @Test
    @DisplayName("OpenAPI 文档元信息精确（标题/描述/版本/联系人）")
    void openApi_infoFieldsExact() {
        OpenAPI api = new SwaggerConfig().codeAtlasOpenAPI();

        assertNotNull(api.getInfo());
        assertEquals("CodeAtlas API", api.getInfo().getTitle());
        assertEquals("AI 驱动的代码地图与架构叙事平台", api.getInfo().getDescription());
        assertEquals("0.1.0", api.getInfo().getVersion());
        assertNotNull(api.getInfo().getContact());
        assertEquals("Zferrys", api.getInfo().getContact().getName());
        assertEquals("https://github.com/zferrys/codeatlas", api.getInfo().getContact().getUrl());
    }

    @Test
    @DisplayName("全局安全要求：附加 JWT 且无 scope")
    void openApi_securityRequirementJwt() {
        OpenAPI api = new SwaggerConfig().codeAtlasOpenAPI();

        assertNotNull(api.getSecurity());
        assertEquals(1, api.getSecurity().size());
        SecurityRequirement requirement = api.getSecurity().get(0);
        assertTrue(requirement.containsKey("JWT"));
        assertTrue(requirement.get("JWT").isEmpty());
    }

    @Test
    @DisplayName("JWT 安全方案：HTTP bearer + 格式 JWT")
    void openApi_jwtSecurityScheme() {
        OpenAPI api = new SwaggerConfig().codeAtlasOpenAPI();

        assertNotNull(api.getComponents());
        assertNotNull(api.getComponents().getSecuritySchemes());
        SecurityScheme scheme = api.getComponents().getSecuritySchemes().get("JWT");
        assertNotNull(scheme);
        assertEquals("JWT", scheme.getName());
        assertEquals(SecurityScheme.Type.HTTP, scheme.getType());
        assertEquals("bearer", scheme.getScheme());
        assertEquals("JWT", scheme.getBearerFormat());
    }
}
