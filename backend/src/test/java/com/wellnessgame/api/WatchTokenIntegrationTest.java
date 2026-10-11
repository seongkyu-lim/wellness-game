package com.wellnessgame.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wellnessgame.auth.JwtTokenService;
import com.wellnessgame.auth.TokenScope;
import com.wellnessgame.character.UserCharacter;
import com.wellnessgame.character.UserCharacterRepository;
import com.wellnessgame.support.MutableClock;
import com.wellnessgame.support.TestClockConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.List;

import static com.wellnessgame.support.TestAuth.bearer;
import static com.wellnessgame.support.TestAuth.bearerToken;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Watch 전용 조회 토큰(#76): 발급은 전체 권한 토큰만, 조회 토큰은 GET 조회 API 만 허용. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestClockConfig.class)
class WatchTokenIntegrationTest {
    private static final String USER = "guest:watch-user";
    private static final String READ_ONLY_KO = "조회 전용 토큰으로는 이 요청을 할 수 없습니다.";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MutableClock clock;

    @Autowired
    private JwtTokenService tokenService;

    @Autowired
    private UserCharacterRepository characterRepository;

    @BeforeEach
    void reset() {
        clock.setInstant(TestClockConfig.DEFAULT_NOW);
        characterRepository.deleteAll();
        characterRepository.save(new UserCharacter(USER));
    }

    @Test
    void fullTokenIssuesReadOnlyWatchTokenWithExpiresIn() throws Exception {
        JsonNode body = issueWatchToken();

        assertThat(fieldNames(body)).containsExactlyInAnyOrder("accessToken", "tokenType", "expiresIn");
        assertThat(body.path("tokenType").asText()).isEqualTo("Bearer");
        assertThat(body.path("expiresIn").asLong()).isEqualTo(Duration.ofHours(12).toSeconds());
        assertThat(tokenService.authenticate(body.path("accessToken").asText()).scope()).isEqualTo(TokenScope.READ);
        assertThat(tokenService.verify(body.path("accessToken").asText())).isEqualTo(USER);
    }

    @Test
    void watchTokenEndpointRequiresBearer() throws Exception {
        mockMvc.perform(post("/api/auth/watch-token")).andExpect(status().isUnauthorized());
    }

    @Test
    void readOnlyTokenCanCallReadApis() throws Exception {
        String watchToken = issueWatchToken().path("accessToken").asText();

        mockMvc.perform(get("/api/characters/me").with(bearerToken(watchToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId", is(USER)));
        mockMvc.perform(get("/api/characters/{userId}", USER).with(bearerToken(watchToken)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/health-activities").param("date", "2026-06-17").with(bearerToken(watchToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId", is(USER)));
    }

    @Test
    void readOnlyTokenCannotSyncReissueOrLogout() throws Exception {
        String watchToken = issueWatchToken().path("accessToken").asText();

        mockMvc.perform(post("/api/health-activities/sync")
                        .with(bearerToken(watchToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"activities\": []}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message", is(READ_ONLY_KO)));
        for (String path : List.of("/api/auth/watch-token", "/api/auth/logout", "/api/auth/logout-all")) {
            mockMvc.perform(post(path).with(bearerToken(watchToken)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.message", is(READ_ONLY_KO)));
        }
        mockMvc.perform(post("/api/health-activities/sync")
                        .with(bearerToken(watchToken))
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "en")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"activities\": []}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message", is("This read-only token can’t be used for this request.")));
    }

    @Test
    void readOnlyTokenExpiresAfterTwelveHours() throws Exception {
        String watchToken = issueWatchToken().path("accessToken").asText();

        clock.setInstant(TestClockConfig.DEFAULT_NOW.plus(Duration.ofHours(12)).plusSeconds(1));
        mockMvc.perform(get("/api/characters/me").with(bearerToken(watchToken)))
                .andExpect(status().isUnauthorized());
    }

    private JsonNode issueWatchToken() throws Exception {
        String response = mockMvc.perform(post("/api/auth/watch-token").with(bearer(tokenService, USER)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response);
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new java.util.ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
