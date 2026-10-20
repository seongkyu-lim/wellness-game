package com.wellnessgame.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Set;

/**
 * Swagger UI 설정(#93). 로그인 API 응답의 accessToken 을 Authorize 에 넣으면 보호 API 를 호출할 수 있다.
 * 켜고 끄기는 application.yml 의 springdoc.*.enabled(APP_API_DOCS_ENABLED)로 한다.
 */
@Configuration
public class OpenApiConfig {
    private static final String BEARER = "bearerAuth";
    /** /api/auth/** 중 인증이 필요한 경로. 나머지 /api/auth/** 는 공개(WebConfig 인증 제외 목록과 같게 유지). */
    private static final Set<String> AUTHENTICATED_AUTH_PATHS = Set.of("/api/auth/logout-all", "/api/auth/watch-token");

    @Bean
    public OpenAPI wellnessGameOpenApi() {
        return new OpenAPI()
                .info(new Info().title("Wellness Game API").version("v1"))
                // 상대 주소: Render 처럼 프록시가 TLS 를 종료해도 Swagger 를 연 출처(https)로 호출한다.
                // forward-headers-strategy 는 X-Forwarded-For 로 remoteAddr 를 바꿔 IP 레이트 리밋을 우회시킬 수 있어 쓰지 않는다.
                .servers(List.of(new Server().url("/")))
                .components(new Components().addSecuritySchemes(BEARER, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")));
    }

    /** 공개 경로(로그인·가입·갱신 등)를 뺀 /api/** 에만 Bearer 요구를 표시한다. */
    @Bean
    public OpenApiCustomizer bearerOnProtectedPaths() {
        return openApi -> {
            if (openApi.getPaths() == null) {
                return;
            }
            openApi.getPaths().forEach((path, item) -> {
                boolean publicAuth = path.startsWith("/api/auth/") && !AUTHENTICATED_AUTH_PATHS.contains(path);
                if (path.startsWith("/api/") && !publicAuth) {
                    item.readOperations().forEach(op -> op.addSecurityItem(new SecurityRequirement().addList(BEARER)));
                }
            });
        };
    }
}
