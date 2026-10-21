package com.wellnessgame.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wellnessgame.activity.HealthActivityRepository;
import com.wellnessgame.auth.JwtTokenService;
import com.wellnessgame.auth.RefreshTokenRepository;
import com.wellnessgame.auth.RefreshTokenService;
import com.wellnessgame.auth.TokenScope;
import com.wellnessgame.auth.UserAuthStateRepository;
import com.wellnessgame.auth.UserCredentialRepository;
import com.wellnessgame.character.UserCharacterRepository;
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

import java.time.Duration;

import static com.wellnessgame.support.TestAuth.bearerToken;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 한 사용자의 전체 HTTP 흐름: 가입 -> 로그인 -> 동기화 -> 캐릭터 조회 -> 리프레시 회전 -> 재사용 감지 -> 로그아웃 / 전체 로그아웃,
 * 그리고 조회 전용(scope=read) 토큰의 제한. 서비스를 직접 부르지 않고 MockMvc 로 실제 필터·인터셉터 체인을 모두 지난다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestClockConfig.class)
class ApiUserJourneyIntegrationTest {
    private static final String PASSWORD = "journey-pass-1234";
    private static final String DATE = "2026-06-17";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private MutableClock clock;
    @Autowired
    private JwtTokenService tokenService;
    @Autowired
    private RefreshTokenService refreshTokenService;
    @Autowired
    private RefreshTokenRepository refreshTokenRepository;
    @Autowired
    private UserCredentialRepository credentialRepository;
    @Autowired
    private UserAuthStateRepository authStateRepository;
    @Autowired
    private HealthActivityRepository activityRepository;
    @Autowired
    private UserCharacterRepository characterRepository;

    @BeforeEach
    void reset() {
        clock.setInstant(TestClockConfig.DEFAULT_NOW);
        refreshTokenRepository.deleteAll();
        authStateRepository.deleteAll();
        credentialRepository.deleteAll();
        activityRepository.deleteAll();
        characterRepository.deleteAll();
    }

    @Test
    void fullJourneyFromSignupToLogoutAll() throws Exception {
        // 1. 가입
        JsonNode signup = ok(post("/api/auth/signup"), """
                {"username":"journey_user","password":"%s","displayName":"여행자"}
                """.formatted(PASSWORD));
        String userId = signup.path("userId").asText();
        assertThat(userId).startsWith("password:");
        assertThat(signup.path("displayName").asText()).isEqualTo("여행자");

        // 2. 같은 계정으로 로그인하면 같은 userId 에 새 세션(새 family)이 열린다
        JsonNode login = ok(post("/api/auth/login"), """
                {"username":"journey_user","password":"%s"}
                """.formatted(PASSWORD));
        assertThat(login.path("userId").asText()).isEqualTo(userId);
        assertThat(login.path("refreshToken").asText()).isNotEqualTo(signup.path("refreshToken").asText());
        String access = login.path("accessToken").asText();
        String refresh1 = login.path("refreshToken").asText();

        // 3. 동기화 전에는 캐릭터가 없다
        mockMvc.perform(get("/api/characters/me").with(bearerToken(access)))
                .andExpect(status().isNotFound());

        // 4. 동기화: 걸음 8000보 = 8*5 + 30 = 70 XP
        mockMvc.perform(post("/api/health-activities/sync").with(bearerToken(access))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(syncBody(8_000)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId", is(userId)))
                .andExpect(jsonPath("$.gainedXp", is(70)))
                .andExpect(jsonPath("$.character.totalXp", is(70)))
                .andExpect(jsonPath("$.character.stats.discipline", is(2)))
                .andExpect(jsonPath("$.goals[0].achieved", is(true)));

        // 5. 같은 값을 다시 보내도 XP 는 늘지 않는다
        mockMvc.perform(post("/api/health-activities/sync").with(bearerToken(access))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(syncBody(8_000)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gainedXp", is(0)))
                .andExpect(jsonPath("$.activityResults[0].duplicate", is(true)));

        // 6. 캐릭터·활동 조회
        mockMvc.perform(get("/api/characters/me").with(bearerToken(access)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.character.totalXp", is(70)))
                .andExpect(jsonPath("$.character.level", is(1)));
        mockMvc.perform(get("/api/health-activities").param("date", DATE).with(bearerToken(access)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activities.length()", is(1)));

        // 7. 리프레시 회전: 새 토큰 쌍, 새 액세스 토큰으로도 접근 가능
        clock.setInstant(TestClockConfig.DEFAULT_NOW.plusSeconds(60));
        JsonNode rotated = ok(post("/api/auth/refresh"), refreshBody(refresh1));
        String refresh2 = rotated.path("refreshToken").asText();
        String access2 = rotated.path("accessToken").asText();
        assertThat(refresh2).isNotEqualTo(refresh1);
        mockMvc.perform(get("/api/characters/me").with(bearerToken(access2))).andExpect(status().isOk());

        // 8. 유예 구간(30초) 지난 뒤 옛 토큰 재사용 -> 401 + family 전체 폐기 + 발급된 액세스 토큰 무효
        clock.setInstant(TestClockConfig.DEFAULT_NOW.plusSeconds(60 + 31));
        mockMvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON).content(refreshBody(refresh1)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message", is("이미 사용된 리프레시 토큰입니다. 보안을 위해 다시 로그인해 주세요.")));
        mockMvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON).content(refreshBody(refresh2)))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/characters/me").with(bearerToken(access2)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message", is("로그인이 해제되었습니다. 다시 로그인해 주세요.")));

        // 9. 다시 로그인해 새 세션을 열고, 이 기기만 로그아웃
        clock.setInstant(TestClockConfig.DEFAULT_NOW.plusSeconds(3_600));
        JsonNode relogin = ok(post("/api/auth/login"), """
                {"username":"journey_user","password":"%s"}
                """.formatted(PASSWORD));
        JsonNode other = ok(post("/api/auth/login"), """
                {"username":"journey_user","password":"%s"}
                """.formatted(PASSWORD));
        mockMvc.perform(post("/api/auth/logout").contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBody(relogin.path("refreshToken").asText())))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBody(relogin.path("refreshToken").asText())))
                .andExpect(status().isUnauthorized());
        // 다른 기기(family)는 영향이 없다
        JsonNode otherRotated = ok(post("/api/auth/refresh"), refreshBody(other.path("refreshToken").asText()));

        // 10. 전체 로그아웃: 모든 리프레시 토큰이 폐기되고 이미 발급된 액세스 토큰도 무효가 된다
        clock.setInstant(TestClockConfig.DEFAULT_NOW.plusSeconds(3_700));
        mockMvc.perform(post("/api/auth/logout-all").with(bearerToken(otherRotated.path("accessToken").asText())))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBody(otherRotated.path("refreshToken").asText())))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/characters/me").with(bearerToken(otherRotated.path("accessToken").asText())))
                .andExpect(status().isUnauthorized());
        assertThat(refreshTokenRepository.findByUserId(userId)).allSatisfy(token -> assertThat(token.getRevokedAt()).isNotNull());

        // 11. 로그아웃 뒤 같은 자격 증명으로 다시 로그인하면 이전 진행도가 그대로다(진행도는 계정에 묶인다)
        clock.setInstant(TestClockConfig.DEFAULT_NOW.plusSeconds(3_800));
        JsonNode back = ok(post("/api/auth/login"), """
                {"username":"journey_user","password":"%s"}
                """.formatted(PASSWORD));
        mockMvc.perform(get("/api/characters/me").with(bearerToken(back.path("accessToken").asText())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.character.totalXp", is(70)));
    }

    @Test
    void retryWithinGraceReturnsSiblingAndFamilyStaysUsable() throws Exception {
        JsonNode login = ok(post("/api/auth/signup"), """
                {"username":"grace_user","password":"%s"}
                """.formatted(PASSWORD));
        String first = login.path("refreshToken").asText();

        JsonNode a = ok(post("/api/auth/refresh"), refreshBody(first));
        clock.setInstant(TestClockConfig.DEFAULT_NOW.plusSeconds(30)); // 유예 경계 포함
        JsonNode b = ok(post("/api/auth/refresh"), refreshBody(first)); // 응답 유실 후 재시도

        assertThat(b.path("refreshToken").asText()).isNotEqualTo(a.path("refreshToken").asText());
        // 두 후계 토큰 모두 계속 쓸 수 있다
        ok(post("/api/auth/refresh"), refreshBody(a.path("refreshToken").asText()));
        ok(post("/api/auth/refresh"), refreshBody(b.path("refreshToken").asText()));
        // 같은 토큰을 세 번째로 쓰면 재사용으로 본다
        mockMvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON).content(refreshBody(first)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void graceBoundaryIsInclusiveAndOneSecondLaterIsReuse() throws Exception {
        String first = signupAndGetRefresh("grace_edge");
        ok(post("/api/auth/refresh"), refreshBody(first));

        clock.setInstant(TestClockConfig.DEFAULT_NOW.plus(Duration.ofSeconds(30)).plusMillis(1));

        mockMvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON).content(refreshBody(first)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message", is("이미 사용된 리프레시 토큰입니다. 보안을 위해 다시 로그인해 주세요.")));
    }

    @Test
    void syncedProgressIsSharedAcrossSessionsOfSameAccount() throws Exception {
        JsonNode signup = ok(post("/api/auth/signup"), """
                {"username":"shared_user","password":"%s"}
                """.formatted(PASSWORD));
        JsonNode login = ok(post("/api/auth/login"), """
                {"username":"shared_user","password":"%s"}
                """.formatted(PASSWORD));

        mockMvc.perform(post("/api/health-activities/sync").with(bearerToken(signup.path("accessToken").asText()))
                        .contentType(MediaType.APPLICATION_JSON).content(syncBody(2_000)))
                .andExpect(jsonPath("$.gainedXp", is(10)));
        // 다른 세션이 더 큰 값을 보내면 차액만 지급
        mockMvc.perform(post("/api/health-activities/sync").with(bearerToken(login.path("accessToken").asText()))
                        .contentType(MediaType.APPLICATION_JSON).content(syncBody(8_000)))
                .andExpect(jsonPath("$.gainedXp", is(60)))
                .andExpect(jsonPath("$.character.totalXp", is(70)));
    }

    // ---- 조회 전용 토큰 ----

    @Test
    void readScopeTokenCanReadButNotWriteOrManageSessions() throws Exception {
        JsonNode signup = ok(post("/api/auth/signup"), """
                {"username":"read_user","password":"%s"}
                """.formatted(PASSWORD));
        String userId = signup.path("userId").asText();
        mockMvc.perform(post("/api/health-activities/sync").with(bearerToken(signup.path("accessToken").asText()))
                        .contentType(MediaType.APPLICATION_JSON).content(syncBody(3_000)))
                .andExpect(status().isOk());
        String read = tokenService.issue(userId, TokenScope.READ).accessToken();

        mockMvc.perform(get("/api/characters/me").with(bearerToken(read))).andExpect(status().isOk());
        mockMvc.perform(get("/api/characters/" + userId).with(bearerToken(read))).andExpect(status().isOk());
        mockMvc.perform(get("/api/health-activities").param("date", DATE).with(bearerToken(read)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activities.length()", is(1)));

        String expected = "조회 전용 토큰으로는 이 요청을 할 수 없습니다.";
        mockMvc.perform(post("/api/health-activities/sync").with(bearerToken(read))
                        .contentType(MediaType.APPLICATION_JSON).content(syncBody(9_000)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message", is(expected)));
        mockMvc.perform(post("/api/auth/logout-all").with(bearerToken(read)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/auth/watch-token").with(bearerToken(read)))
                .andExpect(status().isForbidden());
        // 쓰기가 막혔으니 진행도도 그대로
        assertThat(characterRepository.findByUserId(userId).orElseThrow().getTotalXp()).isEqualTo(15);
    }

    @Test
    void readScopeRefreshFamilyKeepsReadScopeAfterRotation() throws Exception {
        String userId = "kakao:web-read-1";
        String refresh = refreshTokenService.issue(userId, TokenScope.READ).refreshToken();

        JsonNode rotated = ok(post("/api/auth/refresh"), refreshBody(refresh));
        String access = rotated.path("accessToken").asText();

        mockMvc.perform(get("/api/characters/me").with(bearerToken(access))).andExpect(status().isNotFound());
        mockMvc.perform(post("/api/health-activities/sync").with(bearerToken(access))
                        .contentType(MediaType.APPLICATION_JSON).content(syncBody(1_000)))
                .andExpect(status().isForbidden());
        // 회전 결과도 읽기 범위 family 다
        assertThat(tokenService.authenticate(access).scope()).isEqualTo(TokenScope.READ);
    }

    @Test
    void appLogoutRevokesWatchTokensButWebLogoutDoesNot() throws Exception {
        JsonNode signup = ok(post("/api/auth/signup"), """
                {"username":"watch_user","password":"%s"}
                """.formatted(PASSWORD));
        String userId = signup.path("userId").asText();
        String watch = ok(post("/api/auth/watch-token").with(bearerToken(signup.path("accessToken").asText())), null)
                .path("accessToken").asText();
        String webRefresh = refreshTokenService.issue(userId, TokenScope.READ).refreshToken();
        mockMvc.perform(get("/api/characters/me").with(bearerToken(watch))).andExpect(status().isNotFound());

        // 웹(조회 전용) 로그아웃은 Watch 토큰에 영향이 없다
        clock.setInstant(TestClockConfig.DEFAULT_NOW.plusSeconds(10));
        mockMvc.perform(post("/api/auth/logout").contentType(MediaType.APPLICATION_JSON).content(refreshBody(webRefresh)))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/characters/me").with(bearerToken(watch))).andExpect(status().isNotFound());

        // 앱(전체 권한) 로그아웃은 Watch 토큰을 끊는다
        clock.setInstant(TestClockConfig.DEFAULT_NOW.plusSeconds(20));
        mockMvc.perform(post("/api/auth/logout").contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBody(signup.path("refreshToken").asText())))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/characters/me").with(bearerToken(watch)))
                .andExpect(status().isUnauthorized());
    }

    // ---- helpers ----

    private String signupAndGetRefresh(String username) throws Exception {
        return ok(post("/api/auth/signup"), """
                {"username":"%s","password":"%s"}
                """.formatted(username, PASSWORD)).path("refreshToken").asText();
    }

    private static String refreshBody(String token) {
        return "{\"refreshToken\":\"" + token + "\"}";
    }

    private static String syncBody(int steps) {
        return """
                {"date":"%s","activities":[{"type":"STEPS","steps":%d}]}
                """.formatted(DATE, steps);
    }

    /** 200 을 확인하고 본문 JSON 을 돌려준다. body 가 null 이면 본문 없이 보낸다. */
    private JsonNode ok(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request, String body)
            throws Exception {
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        ResultActions result = mockMvc.perform(request).andExpect(status().isOk());
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString());
    }
}
