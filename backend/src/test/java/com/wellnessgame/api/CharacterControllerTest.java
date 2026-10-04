package com.wellnessgame.api;

import com.wellnessgame.auth.JwtTokenService;
import com.wellnessgame.character.UserCharacter;
import com.wellnessgame.character.UserCharacterRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static com.wellnessgame.support.TestAuth.bearer;
import static com.wellnessgame.support.TestAuth.bearerToken;
import static org.hamcrest.Matchers.containsStringIgnoringCase;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class CharacterControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserCharacterRepository characterRepository;

    @Autowired
    private JwtTokenService tokenService;

    @BeforeEach
    void cleanDatabase() {
        characterRepository.deleteAll();
    }

    @Test
    void returnsCharacterForExistingUser() throws Exception {
        UserCharacter character = new UserCharacter("guest:web-user");
        character.addXp(150);
        characterRepository.save(character);

        mockMvc.perform(get("/api/characters/{userId}", "guest:web-user").with(bearer(tokenService, "guest:web-user")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId", is("guest:web-user")))
                .andExpect(jsonPath("$.character.level", is(2)))
                .andExpect(jsonPath("$.character.currentXp", is(50)))
                .andExpect(jsonPath("$.character.totalXp", is(150)))
                .andExpect(jsonPath("$.character.nextLevelXp", is(150)))
                .andExpect(jsonPath("$.character.stats.str", is(0)));
    }

    @Test
    void returnsNotFoundForUnknownUser() throws Exception {
        mockMvc.perform(get("/api/characters/{userId}", "guest:nobody").with(bearer(tokenService, "guest:nobody")))
                .andExpect(status().isNotFound());
    }

    @Test
    void allowsCorsRequestFromWebDevOrigin() throws Exception {
        mockMvc.perform(options("/api/characters/guest:web-user")
                        .header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
    }

    // ---- 인증·인가 (#43) ----

    @Test
    void rejectsRequestWithoutTokenWithBearerChallenge() throws Exception {
        saveCharacter("google:123", 150);

        mockMvc.perform(get("/api/characters/{userId}", "google:123"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.status", is(401)))
                .andExpect(jsonPath("$.error", is("Unauthorized")))
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void rejectsInvalidOrNonBearerToken() throws Exception {
        mockMvc.perform(get("/api/characters/me").with(bearerToken("not-a-jwt")))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"));
        mockMvc.perform(get("/api/characters/me").header("Authorization", "Basic dXNlcjpwYXNz"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"));
    }

    @Test
    void forbidsReadingAnotherUsersCharacter() throws Exception {
        saveCharacter("google:other", 150);

        mockMvc.perform(get("/api/characters/{userId}", "google:other").with(bearer(tokenService, "google:123")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status", is(403)))
                .andExpect(jsonPath("$.message", is("다른 사용자의 데이터입니다.")));
    }

    @Test
    void returnsOwnCharacterWhenPathMatchesToken() throws Exception {
        saveCharacter("google:123", 150);

        mockMvc.perform(get("/api/characters/{userId}", "google:123").with(bearer(tokenService, "google:123")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId", is("google:123")));
    }

    @Test
    void meReturnsTokenSubjectCharacterInSameShape() throws Exception {
        saveCharacter("password:abc", 150);
        saveCharacter("google:other", 999);

        mockMvc.perform(get("/api/characters/me").with(bearer(tokenService, "password:abc")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId", is("password:abc")))
                .andExpect(jsonPath("$.character.level", is(2)))
                .andExpect(jsonPath("$.character.currentXp", is(50)))
                .andExpect(jsonPath("$.character.totalXp", is(150)))
                .andExpect(jsonPath("$.character.nextLevelXp", is(150)))
                .andExpect(jsonPath("$.character.stats.str", is(0)));
    }

    @Test
    void meReturnsNotFoundWhenNoCharacterYet() throws Exception {
        mockMvc.perform(get("/api/characters/me").with(bearer(tokenService, "google:new")))
                .andExpect(status().isNotFound());
    }

    @Test
    void meRequiresToken() throws Exception {
        mockMvc.perform(get("/api/characters/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"));
    }

    @Test
    void allowsPreflightForMeWithAuthorizationHeader() throws Exception {
        mockMvc.perform(options("/api/characters/me")
                        .header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers", "authorization"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"))
                .andExpect(header().string("Access-Control-Allow-Headers", containsStringIgnoringCase("authorization")));
    }

    @Test
    void unauthorizedResponseCarriesCorsHeadersForWebClient() throws Exception {
        mockMvc.perform(get("/api/characters/me").header("Origin", "http://localhost:5173"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
    }

    private void saveCharacter(String userId, int xp) {
        UserCharacter character = new UserCharacter(userId);
        character.addXp(xp);
        characterRepository.save(character);
    }
}
