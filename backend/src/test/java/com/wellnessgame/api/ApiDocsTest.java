package com.wellnessgame.api;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ApiDocsTest {
    @Nested
    @SpringBootTest(properties = {"springdoc.api-docs.enabled=true", "springdoc.swagger-ui.enabled=true"})
    @AutoConfigureMockMvc
    class Enabled {
        @Autowired
        private MockMvc mockMvc;

        @Test
        void exposesOpenApiWithBearerSchemeWithoutAuthentication() throws Exception {
            mockMvc.perform(get("/v3/api-docs"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"))
                    .andExpect(content().string(containsString("/api/auth/login")))
                    .andExpect(jsonPath("$.servers[0].url").value("/"))
                    .andExpect(jsonPath("$.paths['/api/auth/login'].post.security").doesNotExist())
                    .andExpect(jsonPath("$.paths['/api/characters/me'].get.security[0].bearerAuth").exists());
        }

        @Test
        void servesSwaggerUi() throws Exception {
            mockMvc.perform(get("/swagger-ui/index.html"))
                    .andExpect(status().isOk());
        }

        /** #104 Swagger UI 는 같은 출처 스크립트·스타일을 허용하는 CSP 를 받고, 프레임 삽입은 막힌다. */
        @Test
        void swaggerUiGetsCompatibleCspWithFrameAncestorsNone() throws Exception {
            mockMvc.perform(get("/swagger-ui/index.html"))
                    .andExpect(header().string("Content-Security-Policy", containsString("script-src 'self'")))
                    .andExpect(header().string("Content-Security-Policy", containsString("frame-ancestors 'none'")))
                    .andExpect(header().string("X-Frame-Options", "DENY"));
        }

        /**
         * #104 토큰을 브라우저 저장소에 남기지 않는다. 테스트에서는 src/test/resources/application.yml 이 운영 설정을 가리므로
         * 운영 application.yml 값을 직접 읽어 확인하고, 실제 swagger-config 에도 켜져 있지 않은지 본다.
         */
        @Test
        void swaggerUiDoesNotPersistAuthorization() throws Exception {
            YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
            yaml.setResources(new FileSystemResource("src/main/resources/application.yml"));
            assertThat(yaml.getObject()).containsEntry("springdoc.swagger-ui.persist-authorization", false);

            mockMvc.perform(get("/v3/api-docs/swagger-config"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.persistAuthorization").doesNotExist());
        }
    }

    @Nested
    @SpringBootTest
    @AutoConfigureMockMvc
    class DisabledByDefault {
        @Autowired
        private MockMvc mockMvc;

        @Test
        void hidesApiDocsUnlessExplicitlyEnabled() throws Exception {
            mockMvc.perform(get("/v3/api-docs"))
                    .andExpect(status().isNotFound());
        }
    }
}
