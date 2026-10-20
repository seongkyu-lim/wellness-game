package com.wellnessgame.api;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
