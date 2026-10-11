package com.wellnessgame.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wellnessgame.auth.JwtTokenService;
import com.wellnessgame.auth.RefreshToken;
import com.wellnessgame.auth.RefreshTokenCleanupJob;
import com.wellnessgame.auth.RefreshTokenRepository;
import com.wellnessgame.auth.UserCredentialRepository;
import com.wellnessgame.support.MutableClock;
import com.wellnessgame.support.TestClockConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;

import static com.wellnessgame.support.TestAuth.bearerToken;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Auth v2 리프레시 토큰: 발급·회전·재사용 감지·만료·로그아웃·정리(#55). */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestClockConfig.class)
class AuthSessionIntegrationTest {
    private static final long REFRESH_TTL_SECONDS = Duration.ofDays(30).toSeconds();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MutableClock clock;

    @Autowired
    private JwtTokenService tokenService;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private UserCredentialRepository credentialRepository;

    @Autowired
    private RefreshTokenCleanupJob cleanupJob;

    @BeforeEach
    void reset() {
        clock.setInstant(TestClockConfig.DEFAULT_NOW);
        refreshTokenRepository.deleteAll();
        credentialRepository.deleteAll();
    }

    @Test
    void signupIssuesOpaqueRefreshTokenStoredOnlyAsHash() throws Exception {
        JsonNode body = signUp("rt_user1");

        String refreshToken = body.path("refreshToken").asText();
        assertThat(refreshToken).matches("[A-Za-z0-9_-]{43}");
        assertThat(body.path("refreshExpiresIn").asLong()).isEqualTo(REFRESH_TTL_SECONDS);

        List<RefreshToken> rows = refreshTokenRepository.findAll();
        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.getTokenHash()).isEqualTo(sha256Hex(refreshToken)).isNotEqualTo(refreshToken);
            assertThat(row.getUserId()).isEqualTo(body.path("userId").asText());
            assertThat(row.getExpiresAt()).isEqualTo(TestClockConfig.DEFAULT_NOW.plus(Duration.ofDays(30)));
            assertThat(row.getRevokedAt()).isNull();
        });
    }

    @Test
    void refreshRotatesWithinSameFamilyAndIssuesNewAccessToken() throws Exception {
        JsonNode login = signUp("rt_user2");
        String userId = login.path("userId").asText();
        String first = login.path("refreshToken").asText();

        clock.setInstant(TestClockConfig.DEFAULT_NOW.plusSeconds(600));
        JsonNode refreshed = refreshOk(first);

        String second = refreshed.path("refreshToken").asText();
        assertThat(second).isNotEqualTo(first);
        assertThat(refreshed.path("userId").asText()).isEqualTo(userId);
        assertThat(refreshed.path("tokenType").asText()).isEqualTo("Bearer");
        assertThat(refreshed.path("expiresIn").asLong()).isEqualTo(tokenService.ttl().toSeconds());
        assertThat(refreshed.path("refreshExpiresIn").asLong()).isEqualTo(REFRESH_TTL_SECONDS);
        assertThat(tokenService.verify(refreshed.path("accessToken").asText())).isEqualTo(userId);

        RefreshToken old = row(first);
        RefreshToken next = row(second);
        assertThat(old.getRevokedAt()).isNotNull();
        assertThat(old.getReplacedBy()).isEqualTo(next.getId());
        assertThat(next.getFamilyId()).isEqualTo(old.getFamilyId());
        assertThat(next.getRevokedAt()).isNull();
        assertThat(next.getExpiresAt()).isEqualTo(clock.instant().plus(Duration.ofDays(30)));
    }

    @Test
    void reusingRotatedTokenRevokesWholeFamilyButNotOtherSessions() throws Exception {
        String first = signUp("rt_user3").path("refreshToken").asText();
        String otherSession = login("rt_user3").path("refreshToken").asText();
        String second = refreshOk(first).path("refreshToken").asText();

        refresh(first)
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.message", is("이미 사용된 리프레시 토큰입니다. 보안을 위해 다시 로그인해 주세요.")))
                .andExpect(jsonPath("$.accessToken").doesNotExist());

        // 재사용 감지로 family 의 최신 토큰도 폐기됐다.
        refresh(second).andExpect(status().isUnauthorized());
        assertThat(refreshTokenRepository.findByFamilyId(row(first).getFamilyId()))
                .allSatisfy(token -> assertThat(token.getRevokedAt()).isNotNull());
        // 다른 로그인(family)은 영향받지 않는다.
        refreshOk(otherSession);
    }

    @Test
    void expiredRefreshTokenIsUnauthorized() throws Exception {
        String token = signUp("rt_user4").path("refreshToken").asText();

        clock.setInstant(TestClockConfig.DEFAULT_NOW.plus(Duration.ofDays(30)));
        refresh(token)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message", is("리프레시 토큰이 만료되었습니다. 다시 로그인해 주세요.")));
        assertThat(row(token).getRevokedAt()).isNull(); // 만료는 재사용이 아니다
    }

    @Test
    void unknownOrBlankRefreshTokenIsRejected() throws Exception {
        refresh("does-not-exist")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message", is("리프레시 토큰이 유효하지 않습니다. 다시 로그인해 주세요.")));
        mockMvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON).content("{\"refreshToken\": \"\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void refreshIsPublicEvenWithGarbageAuthorizationHeader() throws Exception {
        String token = signUp("rt_user5").path("refreshToken").asText();

        mockMvc.perform(post("/api/auth/refresh")
                        .header("Authorization", "Bearer garbage")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBody(token)))
                .andExpect(status().isOk());
    }

    @Test
    void logoutRevokesOnlyThatFamily() throws Exception {
        JsonNode session = signUp("rt_user6");
        String accessToken = session.path("accessToken").asText();
        String token = session.path("refreshToken").asText();
        String otherDevice = login("rt_user6").path("refreshToken").asText();

        mockMvc.perform(post("/api/auth/logout")
                        .with(bearerToken(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBody(token)))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        refresh(token).andExpect(status().isUnauthorized());
        refreshOk(otherDevice);
    }

    @Test
    void logoutRequiresBearerAndIgnoresMissingBodyOrForeignToken() throws Exception {
        JsonNode alice = signUp("rt_alice");
        String bobToken = signUp("rt_bob").path("refreshToken").asText();

        mockMvc.perform(post("/api/auth/logout").contentType(MediaType.APPLICATION_JSON).content(refreshBody(bobToken)))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/auth/logout").with(bearerToken(alice.path("accessToken").asText())))
                .andExpect(status().isNoContent());
        // 다른 사용자의 리프레시 토큰으로는 폐기되지 않는다.
        mockMvc.perform(post("/api/auth/logout")
                        .with(bearerToken(alice.path("accessToken").asText()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBody(bobToken)))
                .andExpect(status().isNoContent());

        refreshOk(bobToken);
    }

    @Test
    void logoutAllRevokesEveryRefreshTokenOfUserOnly() throws Exception {
        JsonNode first = signUp("rt_user7");
        String second = login("rt_user7").path("refreshToken").asText();
        String otherUser = signUp("rt_user8").path("refreshToken").asText();

        mockMvc.perform(post("/api/auth/logout-all").with(bearerToken(first.path("accessToken").asText())))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/auth/logout-all")).andExpect(status().isUnauthorized());

        refresh(first.path("refreshToken").asText()).andExpect(status().isUnauthorized());
        refresh(second).andExpect(status().isUnauthorized());
        refreshOk(otherUser);
    }

    @Test
    void cleanupDeletesExpiredAndLongRevokedRows() throws Exception {
        String revokedEarly = signUp("rt_user9").path("refreshToken").asText();
        refreshOk(revokedEarly); // revokedEarly 폐기 + 새 토큰(T0 + 30일 만료)

        clock.setInstant(TestClockConfig.DEFAULT_NOW.plus(Duration.ofDays(10)));
        String fresh = login("rt_user9").path("refreshToken").asText();
        assertThat(refreshTokenRepository.count()).isEqualTo(3);

        // T0+10일: 폐기된 지 7일이 지난 revokedEarly 만 지운다.
        cleanupJob.purge();
        assertThat(refreshTokenRepository.findByTokenHash(sha256Hex(revokedEarly))).isEmpty();
        assertThat(refreshTokenRepository.count()).isEqualTo(2);

        // T0+30일: T0 에 회전된 토큰은 만료돼 지우고, T0+10일 토큰은 남긴다.
        clock.setInstant(TestClockConfig.DEFAULT_NOW.plus(Duration.ofDays(30)));
        cleanupJob.purge();
        assertThat(refreshTokenRepository.findAll()).singleElement()
                .satisfies(row -> assertThat(row.getTokenHash()).isEqualTo(sha256Hex(fresh)));
    }

    private JsonNode signUp(String username) throws Exception {
        return postOk("/api/auth/signup", "{\"username\": \"" + username + "\", \"password\": \"password1\"}");
    }

    private JsonNode login(String username) throws Exception {
        return postOk("/api/auth/login", "{\"username\": \"" + username + "\", \"password\": \"password1\"}");
    }

    private JsonNode refreshOk(String refreshToken) throws Exception {
        return postOk("/api/auth/refresh", refreshBody(refreshToken));
    }

    private ResultActions refresh(String refreshToken) throws Exception {
        return mockMvc.perform(post("/api/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(refreshBody(refreshToken)));
    }

    private JsonNode postOk(String path, String json) throws Exception {
        String response = mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response);
    }

    private RefreshToken row(String refreshToken) {
        return refreshTokenRepository.findByTokenHash(sha256Hex(refreshToken)).orElseThrow();
    }

    private static String refreshBody(String refreshToken) {
        return "{\"refreshToken\": \"" + refreshToken + "\"}";
    }

    private static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
