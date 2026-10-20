package com.wellnessgame.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Swagger UI 설정(#93). 로그인 API 응답의 accessToken 을 Authorize 에 넣으면 보호 API 를 호출할 수 있다.
 * 켜고 끄기는 application.yml 의 springdoc.*.enabled(APP_API_DOCS_ENABLED)로 한다.
 */
@Configuration
public class OpenApiConfig {
    private static final String BEARER = "bearerAuth";

    @Bean
    public OpenAPI wellnessGameOpenApi() {
        return new OpenAPI()
                .info(new Info().title("Wellness Game API").version("v1"))
                .components(new Components().addSecuritySchemes(BEARER, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER));
    }
}
