package com.wellnessgame.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** #104 모든 응답(성공·오류·preflight)에 보안 헤더가 실리고 CORS 는 그대로 동작한다. */
@SpringBootTest
@AutoConfigureMockMvc
class SecurityHeadersFilterTest {
    private static final String ORIGIN = "http://localhost:5173";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void addsSecurityHeadersToSuccessAndErrorResponses() throws Exception {
        expectSecurityHeaders(mockMvc.perform(get("/healthz")).andExpect(status().isOk()));
        expectSecurityHeaders(mockMvc.perform(get("/api/characters/me")).andExpect(status().isUnauthorized()));
        expectSecurityHeaders(mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest()));
    }

    @Test
    void corsStillWorksAlongsideSecurityHeaders() throws Exception {
        expectSecurityHeaders(mockMvc.perform(options("/api/auth/login")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ORIGIN)));
        expectSecurityHeaders(mockMvc.perform(get("/api/characters/me").header(HttpHeaders.ORIGIN, ORIGIN))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ORIGIN)));
    }

    private static void expectSecurityHeaders(ResultActions result) throws Exception {
        result.andExpect(header().string("Strict-Transport-Security", "max-age=31536000; includeSubDomains"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Content-Security-Policy", "frame-ancestors 'none'"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"));
    }
}
