package com.wellnessgame.api;

import com.wellnessgame.auth.JwtTokenService;
import com.wellnessgame.auth.TokenScope;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static com.wellnessgame.support.TestAuth.bearerToken;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 오류 응답 계약: 상태 코드별 body 형태({timestamp, status, error, message})와 한국어(기본)·영어(Accept-Language) 문구,
 * 그리고 내부 정보(예외 클래스·스택·파서 상세)가 응답에 새지 않는지.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestClockConfig.class)
class ApiErrorContractIntegrationTest {
    private static final String KO = "ko";
    private static final String EN = "en-US,en;q=0.9";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JwtTokenService tokenService;
    @Autowired
    private MutableClock clock;

    @BeforeEach
    void resetClock() {
        clock.setInstant(TestClockConfig.DEFAULT_NOW);
    }

    // ---- 401 ----

    @Test
    void missingTokenIs401WithBearerChallengeInBothLanguages() throws Exception {
        expectError(perform(get("/api/characters/me"), KO), 401, "Unauthorized", "인증이 필요합니다. 로그인해 주세요.")
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"));
        expectError(perform(get("/api/characters/me"), EN), 401, "Unauthorized", "Please sign in to continue.")
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"));
    }

    @Test
    void nonBearerSchemeAndGarbageTokenAreInvalid() throws Exception {
        expectError(perform(get("/api/characters/me").header(HttpHeaders.AUTHORIZATION, "Basic dXNlcjpwdw=="), KO),
                401, "Unauthorized", "토큰이 유효하지 않습니다.");
        expectError(perform(get("/api/characters/me").header(HttpHeaders.AUTHORIZATION, "Bearer "), EN),
                401, "Unauthorized", "Your session is invalid. Please sign in again.");
        expectError(perform(get("/api/characters/me").with(bearerToken("not.a.jwt")), EN),
                401, "Unauthorized", "Your session is invalid. Please sign in again.");
    }

    @Test
    void bearerSchemeIsCaseInsensitive() throws Exception {
        String token = tokenService.issue("lowercase-scheme").accessToken();

        perform(get("/api/characters/me").header(HttpHeaders.AUTHORIZATION, "bearer " + token), KO)
                .andExpect(status().isNotFound());
    }

    @Test
    void expiredTokenIs401WithExpiredMessage() throws Exception {
        String token = tokenService.issue("expiring-user").accessToken();
        clock.setInstant(TestClockConfig.DEFAULT_NOW.plus(tokenService.ttl()).plusSeconds(1));

        expectError(perform(get("/api/characters/me").with(bearerToken(token)), KO),
                401, "Unauthorized", "토큰이 만료되었습니다. 다시 로그인해 주세요.");
        expectError(perform(get("/api/characters/me").with(bearerToken(token)), EN),
                401, "Unauthorized", "Your session has expired. Please sign in again.");
    }

    @Test
    void tokenIsValidUntilTheLastSecondBeforeExpiry() throws Exception {
        String token = tokenService.issue("edge-user").accessToken();
        clock.setInstant(TestClockConfig.DEFAULT_NOW.plus(tokenService.ttl()).minusSeconds(1));

        perform(get("/api/characters/me").with(bearerToken(token)), KO).andExpect(status().isNotFound());
    }

    @Test
    void wrongPasswordOnLoginIs400WithoutRevealingWhetherUserExists() throws Exception {
        MockHttpServletRequestBuilder login = post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"ghost_user\",\"password\":\"whatever-123\"}");

        expectError(perform(login, KO), 400, "Bad Request", "아이디 또는 비밀번호가 올바르지 않습니다.");
        expectError(perform(login, EN), 400, "Bad Request", "Incorrect username or password.");
    }

    @Test
    void refreshWithUnknownTokenIs401() throws Exception {
        MockHttpServletRequestBuilder refresh = post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + "x".repeat(43) + "\"}");

        expectError(perform(refresh, KO), 401, "Unauthorized", "리프레시 토큰이 유효하지 않습니다. 다시 로그인해 주세요.")
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"));
        expectError(perform(refresh, EN), 401, "Unauthorized", "Your refresh token is invalid. Please sign in again.");
    }

    // ---- 403 ----

    @Test
    void readingAnotherUsersDataIs403() throws Exception {
        String token = tokenService.issue("owner-user").accessToken();

        expectError(perform(get("/api/characters/someone-else").with(bearerToken(token)), KO),
                403, "Forbidden", "다른 사용자의 데이터입니다.");
        expectError(perform(get("/api/characters/someone-else").with(bearerToken(token)), EN),
                403, "Forbidden", "You can’t access another user’s data.");
    }

    @Test
    void syncingForAnotherUserIs403() throws Exception {
        String token = tokenService.issue("owner-user").accessToken();
        MockHttpServletRequestBuilder sync = post("/api/health-activities/sync").with(bearerToken(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"victim\",\"date\":\"2026-06-17\",\"activities\":[{\"type\":\"STEPS\",\"steps\":1000}]}");

        expectError(perform(sync, EN), 403, "Forbidden", "You can’t access another user’s data.");
    }

    @Test
    void readOnlyTokenOnWriteEndpointIs403() throws Exception {
        String read = tokenService.issue("reader", TokenScope.READ).accessToken();

        expectError(perform(post("/api/health-activities/sync").with(bearerToken(read))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"), KO),
                403, "Forbidden", "조회 전용 토큰으로는 이 요청을 할 수 없습니다.");
        expectError(perform(post("/api/auth/logout-all").with(bearerToken(read)), EN),
                403, "Forbidden", "This read-only token can’t be used for this request.");
    }

    // ---- 400 ----

    @Test
    void bodyValidationErrorsNameTheFieldAndFollowLanguage() throws Exception {
        MockHttpServletRequestBuilder signup = post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"ok_user\",\"password\":\"short\"}");

        expectError(perform(signup, KO), 400, "Bad Request", "password: 비밀번호는 8자 이상이어야 합니다.");
        expectError(perform(signup, EN), 400, "Bad Request", "password: Password must be at least 8 characters.");
    }

    @Test
    void usernamePatternAndPasswordByteLimitAreValidated() throws Exception {
        expectError(perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"a b\",\"password\":\"long-enough-pw\"}"), EN),
                400, "Bad Request", "username: Username must be 4–32 characters using letters, numbers, or underscores.");
        // 한글 25자 = 75바이트 > 72바이트 (문자 수로는 8자 이상 72자 이하라 @Size 는 통과)
        expectError(perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"ok_user\",\"password\":\"" + "가".repeat(25) + "\"}"), EN),
                400, "Bad Request", "password: Password must be at most 72 bytes in UTF-8 (Korean characters use 3 bytes each).");
    }

    @Test
    void malformedJsonIs400WithGenericMessageAndNoParserDetails() throws Exception {
        MockHttpServletRequestBuilder broken = post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\": \"abc\", ");

        expectError(perform(broken, KO), 400, "Bad Request", "요청 본문을 읽을 수 없습니다. 형식을 확인해 주세요.")
                .andExpect(jsonPath("$.message", not(containsString("Jackson"))))
                .andExpect(jsonPath("$.message", not(containsString("JsonParseException"))));
        expectError(perform(broken, EN), 400, "Bad Request", "The request body couldn’t be read. Please check the format.");
    }

    @Test
    void unknownEnumValueIsUnreadableBodyNotInternalError() throws Exception {
        String token = tokenService.issue("enum-user").accessToken();

        expectError(perform(post("/api/health-activities/sync").with(bearerToken(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"date\":\"2026-06-17\",\"activities\":[{\"type\":\"FLYING\"}]}"), EN),
                400, "Bad Request", "The request body couldn’t be read. Please check the format.");
    }

    @Test
    void missingAndMalformedQueryParametersAre400() throws Exception {
        String token = tokenService.issue("query-user").accessToken();

        expectError(perform(get("/api/health-activities").with(bearerToken(token)), KO),
                400, "Bad Request", "필수 요청 값이 빠졌습니다.");
        expectError(perform(get("/api/health-activities").param("date", "2026-13-45").with(bearerToken(token)), EN),
                400, "Bad Request", "A request parameter has an invalid value.");
        expectError(perform(get("/api/health-activities").param("date", "yesterday").with(bearerToken(token)), KO),
                400, "Bad Request", "요청 값의 형식이 올바르지 않습니다.");
    }

    @Test
    void structuralSyncErrorsAre400WithLocalizedMessage() throws Exception {
        String token = tokenService.issue("sync-user").accessToken();

        expectError(perform(post("/api/health-activities/sync").with(bearerToken(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"date\":\"2026-06-20\",\"activities\":[{\"type\":\"STEPS\",\"steps\":1}]}"), EN),
                400, "Bad Request", "The sync date must be within 1 day of today.");
        expectError(perform(post("/api/health-activities/sync").with(bearerToken(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"date\":\"2026-06-17\",\"activities\":[{\"type\":\"STEPS\"}]}"), KO),
                400, "Bad Request", "STEPS 활동에는 steps가 필요합니다.");
        expectError(perform(post("/api/health-activities/sync").with(bearerToken(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"date\":\"2026-06-17\",\"activities\":[{\"type\":\"STEPS\",\"steps\":-1}]}"), EN),
                400, "Bad Request", "activities[0].steps: must be 0 or greater.");
    }

    // ---- 404 ----

    @Test
    void characterNotFoundIs404() throws Exception {
        String token = tokenService.issue("no-character-yet").accessToken();

        perform(get("/api/characters/me").with(bearerToken(token)), KO).andExpect(status().isNotFound());
        perform(get("/api/characters/no-character-yet").with(bearerToken(token)), EN).andExpect(status().isNotFound());
    }

    @Test
    void unknownApiRouteWithValidTokenIs404AndWithoutTokenIs401() throws Exception {
        String token = tokenService.issue("route-user").accessToken();

        perform(get("/api/does-not-exist").with(bearerToken(token)), KO).andExpect(status().isNotFound());
        // 인증은 라우팅보다 먼저라서 존재 여부를 토큰 없이 알아내지 못한다.
        perform(get("/api/does-not-exist"), KO).andExpect(status().isUnauthorized());
    }

    // ---- 415 / 405 ----

    @Test
    void unsupportedContentTypeIs415() throws Exception {
        perform(post("/api/auth/login").contentType(MediaType.TEXT_PLAIN).content("username=a&password=b"), KO)
                .andExpect(status().isUnsupportedMediaType());
        perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_XML).content("<a/>"), EN)
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void missingContentTypeOnJsonEndpointIs415() throws Exception {
        perform(post("/api/auth/login").content("{\"username\":\"a\",\"password\":\"b\"}"), KO)
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void wrongMethodIs405() throws Exception {
        String token = tokenService.issue("method-user").accessToken();

        perform(get("/api/auth/refresh").with(bearerToken(token)), KO)
                .andExpect(status().isMethodNotAllowed());
        perform(post("/api/characters/me").with(bearerToken(token)), KO)
                .andExpect(status().isMethodNotAllowed());
    }

    // ---- 공통 계약 ----

    @Test
    void errorTimestampIsIsoInstantAndBodyHasExactlyTheDocumentedFields() throws Exception {
        perform(get("/api/characters/me"), KO)
                .andExpect(jsonPath("$.timestamp", matchesPattern("\\d{4}-\\d{2}-\\d{2}T[\\d:.]+Z")))
                .andExpect(jsonPath("$.length()", is(4)));
    }

    @Test
    void errorResponsesCarrySecurityHeaders() throws Exception {
        for (ResultActions result : new ResultActions[]{
                perform(get("/api/characters/me"), KO),
                perform(post("/api/auth/login").contentType(MediaType.TEXT_PLAIN).content("x"), KO)}) {
            result.andExpect(header().string("X-Content-Type-Options", "nosniff"))
                    .andExpect(header().string("X-Frame-Options", "DENY"))
                    .andExpect(header().string("Referrer-Policy", "no-referrer"))
                    .andExpect(header().string("Content-Security-Policy", "frame-ancestors 'none'"))
                    .andExpect(header().string("Strict-Transport-Security", "max-age=31536000; includeSubDomains"));
        }
    }

    @Test
    void unsupportedLanguageFallsBackToKorean() throws Exception {
        expectError(perform(get("/api/characters/me"), "fr-FR,fr;q=0.9"), 401, "Unauthorized",
                "인증이 필요합니다. 로그인해 주세요.");
    }

    @Test
    void notFoundAndBadRequestBodiesDoNotLeakInternalDetails() throws Exception {
        String token = tokenService.issue("leak-user").accessToken();

        perform(post("/api/health-activities/sync").with(bearerToken(token)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"date\":\"not-a-date\",\"activities\":[]}"), EN)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", not(anyOf(containsString("Exception"), containsString("java."),
                        containsString("com.wellnessgame"), containsString("at ")))));
    }

    // ---- helpers ----

    private ResultActions perform(MockHttpServletRequestBuilder request, String acceptLanguage) throws Exception {
        // 같은 빌더를 언어만 바꿔 다시 쓰므로 헤더를 덧붙이지 않고 교체한다(후처리기는 등록 순서대로 실행되고 마지막 것이 이긴다).
        return mockMvc.perform(request.with(servletRequest -> {
            servletRequest.removeHeader(HttpHeaders.ACCEPT_LANGUAGE);
            servletRequest.addHeader(HttpHeaders.ACCEPT_LANGUAGE, acceptLanguage);
            return servletRequest;
        }));
    }

    private ResultActions perform(MockHttpServletRequestBuilder request) throws Exception {
        return perform(request, KO);
    }

    private static ResultActions expectError(ResultActions result, int status, String error, String message)
            throws Exception {
        return result.andExpect(status().is(status))
                .andExpect(jsonPath("$.status", is(status)))
                .andExpect(jsonPath("$.error", is(error)))
                .andExpect(jsonPath("$.message", is(message)))
                .andExpect(jsonPath("$.timestamp").exists());
    }
}
