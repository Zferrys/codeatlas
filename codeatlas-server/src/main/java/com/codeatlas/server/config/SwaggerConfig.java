package com.codeatlas.server.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SwaggerConfig {

    @Bean
    public OpenAPI codeAtlasOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("CodeAtlas API")
                        .description("AI 驱动的代码地图与架构叙事平台")
                        .version("0.1.0")
                        .contact(new Contact()
                                .name("Zferrys")
                                .url("https://github.com/zferrys/codeatlas")))
                .addSecurityItem(new SecurityRequirement().addList("JWT"))
                .schemaRequirement("JWT", new SecurityScheme()
                        .name("JWT")
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT"));
    }
}
