package com.wellnessgame.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wellnessgame.auth.JwtTokenService;
import com.wellnessgame.auth.RefreshToken;
import com.wellnessgame.auth.RefreshTokenCleanupJob;
import com.wellnessgame.auth.RefreshTokenRepository;
import com.wellnessgame.auth.RefreshTokenService;
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
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.wellnessgame.support.TestAuth.bearerToken;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Auth v2 리프레시 토큰: 발급·회전·유예 구간·재사용 감지·절대 만료·로그아웃·액세스 토큰 무효화·정리(#55). */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestClockConfig.class)
class AuthSessionIntegrationTest {
    private static final long REFRESH_TTL_SECONDS = Duration.ofDays(30).toSeconds();
    private static final Duration GRACE = Duration.ofSeconds(30);

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

    @Autowired
    private RefreshTokenService refreshTokenService;

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
    void reusingRotatedTokenAfterGraceRevokesWholeFamilyAndIssuedAccessTokens() throws Exception {
        JsonNode session = signUp("rt_user3");
        String first = session.path("refreshToken").asText();
        String otherSession = login("rt_user3").path("refreshToken").asText();
        String second = refreshOk(first).path("refreshToken").asText();

        clock.setInstant(TestClockConfig.DEFAULT_NOW.plus(GRACE).plusSeconds(1));
        refresh(first)
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.message", is("이미 사용된 리프레시 토큰입니다. 보안을 위해 다시 로그인해 주세요.")))
                .andExpect(jsonPath("$.accessToken").doesNotExist());

        // 재사용 감지로 family 의 최신 토큰도 폐기됐다.
        refresh(second).andExpect(status().isUnauthorized());
        assertThat(refreshTokenRepository.findByFamilyId(row(first).getFamilyId()))
                .allSatisfy(token -> assertThat(token.getRevokedAt()).isNotNull());
        // 그 전에 발급된 액세스 토큰도 거부된다(tokens_valid_after).
        mockMvc.perform(get("/api/characters/me").with(bearerToken(session.path("accessToken").asText())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message", is("로그인이 해제되었습니다. 다시 로그인해 주세요.")));
        // 다른 로그인(family)의 리프레시는 영향받지 않아 새 액세스 토큰을 받을 수 있다.
        refreshOk(otherSession);
    }

    /** 응답 유실 후 재시도: 유예 구간 안이면 형제 토큰을 주고, 먼저 받은 후계 토큰도 계속 유효하다. */
    @Test
    void retryWithinGraceIssuesSiblingAndKeepsSuccessorValid() throws Exception {
        String first = signUp("rt_grace1").path("refreshToken").asText();
        String lostSuccessor = refreshOk(first).path("refreshToken").asText();

        clock.setInstant(TestClockConfig.DEFAULT_NOW.plus(GRACE));
        JsonNode retried = refreshOk(first);
        String sibling = retried.path("refreshToken").asText();

        assertThat(sibling).isNotIn(first, lostSuccessor);
        assertThat(row(sibling).getFamilyId()).isEqualTo(row(first).getFamilyId());
        assertThat(row(first).getReplacedBy()).isEqualTo(row(lostSuccessor).getId());
        refreshOk(lostSuccessor);
        refreshOk(sibling);
    }

    @Test
    void retryAfterLogoutWithinGraceIsRejectedWithoutReuseHandling() throws Exception {
        String first = signUp("rt_grace2").path("refreshToken").asText();
        String second = refreshOk(first).path("refreshToken").asText();
        logout(second).andExpect(status().isNoContent());

        refresh(first)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message", is("리프레시 토큰이 유효하지 않습니다. 다시 로그인해 주세요.")));
    }

    /** 같은 토큰으로 동시에 회전해도(행 잠금으로 직렬화) 둘 다 성공하고 family 는 유지된다. */
    @Test
    void concurrentRefreshWithSameTokenBothSucceed() throws Exception {
        String first = signUp("rt_concurrent").path("refreshToken").asText();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            Callable<RefreshTokenService.Rotation> rotate = () -> {
                start.await();
                return refreshTokenService.rotate(first);
            };
            Future<RefreshTokenService.Rotation> a = executor.submit(rotate);
            Future<RefreshTokenService.Rotation> b = executor.submit(rotate);
            start.countDown();

            String tokenA = a.get(30, TimeUnit.SECONDS).refreshToken().refreshToken();
            String tokenB = b.get(30, TimeUnit.SECONDS).refreshToken().refreshToken();
            assertThat(tokenA).isNotEqualTo(tokenB);
            refreshOk(tokenA);
            refreshOk(tokenB);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void familyHasAbsoluteLifetimeEvenWhenRotated() throws Exception {
        String token = signUp("rt_absolute").path("refreshToken").asText();
        for (int days : new int[]{29, 58, 87}) {
            clock.setInstant(TestClockConfig.DEFAULT_NOW.plus(Duration.ofDays(days)));
            JsonNode body = refreshOk(token);
            token = body.path("refreshToken").asText();
            long expected = Math.min(REFRESH_TTL_SECONDS, Duration.ofDays(90 - days).toSeconds());
            assertThat(body.path("refreshExpiresIn").asLong()).isEqualTo(expected);
        }
        assertThat(row(token).getExpiresAt()).isEqualTo(TestClockConfig.DEFAULT_NOW.plus(Duration.ofDays(90)));

        clock.setInstant(TestClockConfig.DEFAULT_NOW.plus(Duration.ofDays(90)));
        refresh(token).andExpect(status().isUnauthorized());
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

    /** 로그아웃은 공개 경로다. 토큰 소유가 권한 증명이며, 그 토큰의 family 만 폐기한다. */
    @Test
    void logoutWithoutBearerRevokesOnlyPresentedFamily() throws Exception {
        String token = signUp("rt_user6").path("refreshToken").asText();
        String otherDevice = login("rt_user6").path("refreshToken").asText();
        String otherUser = signUp("rt_user6b").path("refreshToken").asText();

        logout(token).andExpect(status().isNoContent()).andExpect(content().string(""));

        refresh(token).andExpect(status().isUnauthorized());
        refreshOk(otherDevice);
        refreshOk(otherUser);
    }

    @Test
    void logoutAnswersNoContentForUnknownOrRevokedTokensAndIgnoresBearer() throws Exception {
        JsonNode alice = signUp("rt_alice");
        String bobToken = signUp("rt_bob").path("refreshToken").asText();

        logout("does-not-exist").andExpect(status().isNoContent());
        logout(bobToken).andExpect(status().isNoContent());
        logout(bobToken).andExpect(status().isNoContent()); // 이미 폐기된 토큰도 같은 응답
        // Bearer(남의 액세스 토큰)가 와도 무시하고 body 의 토큰만 본다.
        mockMvc.perform(post("/api/auth/logout")
                        .with(bearerToken(alice.path("accessToken").asText()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBody("does-not-exist")))
                .andExpect(status().isNoContent());
        refreshOk(alice.path("refreshToken").asText());
        mockMvc.perform(post("/api/auth/logout").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void logoutAllRevokesEveryRefreshAndPreviouslyIssuedAccessTokenOfUserOnly() throws Exception {
        JsonNode first = signUp("rt_user7");
        String firstAccess = first.path("accessToken").asText();
        String second = login("rt_user7").path("refreshToken").asText();
        String watchToken = objectMapper.readTree(mockMvc.perform(post("/api/auth/watch-token").with(bearerToken(firstAccess)))
                .andReturn().getResponse().getContentAsString()).path("accessToken").asText();
        JsonNode otherUser = signUp("rt_user8");

        // JWT iat 는 초 단위라 같은 초에 발급된 토큰은 기준을 통과한다. 1초 뒤에 로그아웃한다.
        clock.setInstant(TestClockConfig.DEFAULT_NOW.plusSeconds(1));
        mockMvc.perform(post("/api/auth/logout-all").with(bearerToken(firstAccess)))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/auth/logout-all")).andExpect(status().isUnauthorized());

        refresh(first.path("refreshToken").asText()).andExpect(status().isUnauthorized());
        refresh(second).andExpect(status().isUnauthorized());
        for (String revoked : new String[]{firstAccess, watchToken}) {
            mockMvc.perform(get("/api/characters/me").with(bearerToken(revoked)))
                    .andExpect(status().isUnauthorized());
        }
        // 다른 사용자, 그리고 로그아웃 뒤 다시 로그인한 토큰은 쓸 수 있다.
        mockMvc.perform(get("/api/characters/me").with(bearerToken(otherUser.path("accessToken").asText())))
                .andExpect(status().isNotFound());
        String fresh = login("rt_user7").path("accessToken").asText();
        mockMvc.perform(get("/api/characters/me").with(bearerToken(fresh)))
                .andExpect(status().isNotFound());
        refreshOk(otherUser.path("refreshToken").asText());
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

    private ResultActions logout(String refreshToken) throws Exception {
        return mockMvc.perform(post("/api/auth/logout")
                .contentType(MediaType.APPLICATION_JSON)
                .content(refreshBody(refreshToken)));
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
