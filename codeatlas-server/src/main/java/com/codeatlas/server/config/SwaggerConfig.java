package com.codeatlas.server.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import springfox.documentation.builders.ApiInfoBuilder;
import springfox.documentation.builders.PathSelectors;
import springfox.documentation.builders.RequestHandlerSelectors;
import springfox.documentation.service.ApiInfo;
import springfox.documentation.service.ApiKey;
import springfox.documentation.service.Contact;
import springfox.documentation.spi.DocumentationType;
import springfox.documentation.spring.web.plugins.Docket;
import springfox.documentation.swagger2.annotations.EnableSwagger2WebMvc;

import java.util.Collections;

@Configuration
@EnableSwagger2WebMvc
public class SwaggerConfig {

    @Bean
    public Docket apiDocket() {
        return new Docket(DocumentationType.SWAGGER_2)
                .apiInfo(apiInfo())
                .select()
                .apis(RequestHandlerSelectors.basePackage("com.codeatlas.server.controller"))
                .paths(PathSelectors.any())
                .build()
                .securitySchemes(Collections.singletonList(
                        new ApiKey("JWT", "Authorization", "header")));
    }

    private ApiInfo apiInfo() {
        return new ApiInfoBuilder()
                .title("CodeAtlas API")
                .description("AI 驱动的代码地图与架构叙事平台")
                .version("0.1.0")
                .contact(new Contact("Zferrys", "https://github.com/zferrys/codeatlas", ""))
                .build();
    }
}
