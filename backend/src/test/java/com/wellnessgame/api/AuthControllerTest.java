package com.wellnessgame.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AuthControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @Test
    void rejectsUnsupportedProvider() throws Exception {
        mockMvc.perform(post("/api/auth/apple")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code": "auth-code", "redirectUri": "http://localhost:5173"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void returnsServiceUnavailableWhenProviderNotConfigured() throws Exception {
        mockMvc.perform(post("/api/auth/kakao")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code": "auth-code", "redirectUri": "http://localhost:5173"}
                                """))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void rejectsBlankCode() throws Exception {
        mockMvc.perform(post("/api/auth/kakao")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code": "", "redirectUri": "http://localhost:5173"}
                                """))
                .andExpect(status().isBadRequest());
    }
}
