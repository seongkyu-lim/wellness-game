package com.wellnessgame.ratelimit;

import com.wellnessgame.auth.JwtTokenService;
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

import static com.wellnessgame.support.TestAuth.bearer;
import static com.wellnessgame.support.TestRequests.fromIp;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * #48 레이트 리밋: 경로별 한도, 429 + Retry-After + 기존 에러 body, CORS 헤더, 윈도우 경과 후 재허용, 키 분리.
 * 테스트끼리 리미터 상태를 공유하므로 각 테스트는 서로 다른 IP·사용자를 쓴다.
 */
@SpringBootTest(properties = {
        "app.rate-limit.sync-per-minute=2",
        "app.rate-limit.auth-per-minute=4",
        "app.rate-limit.login-per-minute=2",
        "app.rate-limit.trust-forwarded-header=false"
})
@AutoConfigureMockMvc
@Import(TestClockConfig.class)
class RateLimitIntegrationTest {
    private static final String ORIGIN = "http://localhost:5173";
    private static final String BAD_LOGIN = """
            {"username": "nobody_rl", "password": "wrong-password"}
            """;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MutableClock clock;

    @Autowired
    private JwtTokenService tokenService;

    @BeforeEach
    void resetClock() {
        clock.setInstant(TestClockConfig.DEFAULT_NOW);
    }

    @Test
    void loginIsLimitedPerIpWith429RetryAfterErrorBodyAndCorsHeaders() throws Exception {
        login("198.51.100.1").andExpect(status().isBadRequest());
        login("198.51.100.1").andExpect(status().isBadRequest());

        login("198.51.100.1")
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "60"))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ORIGIN))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, containsString("Retry-After")))
                .andExpect(jsonPath("$.status", is(429)))
                .andExpect(jsonPath("$.error", is("Too Many Requests")))
                .andExpect(jsonPath("$.message", is("요청이 너무 많아요. 60초 후에 다시 시도해 주세요.")))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.accessToken").doesNotExist());

        login("198.51.100.2").andExpect(status().isBadRequest());
    }

    @Test
    void signupSharesStrictLimitWithLogin() throws Exception {
        login("198.51.100.10").andExpect(status().isBadRequest());
        mockMvc.perform(fromIp(post("/api/auth/signup"), "198.51.100.10")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\": \"rl_signup_1\", \"password\": \"password1\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(fromIp(post("/api/auth/signup"), "198.51.100.10")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\": \"rl_signup_2\", \"password\": \"password1\"}"))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void allowsAgainAfterWindowElapses() throws Exception {
        login("198.51.100.20");
        login("198.51.100.20");
        clock.setInstant(TestClockConfig.DEFAULT_NOW.plusSeconds(45));
        login("198.51.100.20")
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "15"));

        clock.setInstant(TestClockConfig.DEFAULT_NOW.plusSeconds(60));

        login("198.51.100.20").andExpect(status().isBadRequest());
    }

    /** {@code /api/auth/{provider}} 코드 교환도 /api/auth/** 한도에 포함된다. */
    @Test
    void providerCodeExchangeCountsTowardWholeAuthLimitPerIp() throws Exception {
        for (int i = 0; i < 4; i++) {
            socialCodeExchange("198.51.100.30").andExpect(status().is(not(429)));
        }

        socialCodeExchange("198.51.100.30").andExpect(status().isTooManyRequests());
        // 같은 IP 의 로그인도 /api/auth/** 한도에 걸린다.
        login("198.51.100.30").andExpect(status().isTooManyRequests());
        socialCodeExchange("198.51.100.31").andExpect(status().isServiceUnavailable());
    }

    @Test
    void ipv6ClientsInSameSlash64ShareLimit() throws Exception {
        login("2001:db8:77:1::1").andExpect(status().isBadRequest());
        login("2001:db8:77:1::2").andExpect(status().isBadRequest());

        login("2001:db8:77:1:ffff::3").andExpect(status().isTooManyRequests());
        login("2001:db8:77:2::1").andExpect(status().isBadRequest());
    }

    @Test
    void untrustedForwardedHeaderCannotBypassIpLimit() throws Exception {
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(loginRequest("198.51.100.40").header("X-Forwarded-For", "203.0.113." + i))
                    .andExpect(status().isBadRequest());
        }

        mockMvc.perform(loginRequest("198.51.100.40").header("X-Forwarded-For", "203.0.113.99"))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void syncIsLimitedPerAuthenticatedUser() throws Exception {
        sync("rl-user-1").andExpect(status().isBadRequest());
        sync("rl-user-1").andExpect(status().isBadRequest());

        sync("rl-user-1")
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "60"));
        sync("rl-user-2").andExpect(status().isBadRequest());
    }

    @Test
    void unauthenticatedSyncIsRejectedBeforeCounting() throws Exception {
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/api/health-activities/sync").contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Test
    void preflightIsNotCounted() throws Exception {
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(fromIp(options("/api/auth/login"), "198.51.100.50")
                            .header(HttpHeaders.ORIGIN, ORIGIN)
                            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                    .andExpect(status().isOk());
        }

        login("198.51.100.50").andExpect(status().isBadRequest());
    }

    @Test
    void rateLimitMessageFollowsAcceptLanguage() throws Exception {
        login("198.51.100.60");
        login("198.51.100.60");

        mockMvc.perform(loginRequest("198.51.100.60").header(HttpHeaders.ACCEPT_LANGUAGE, "en"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message", is("Too many requests. Please try again in 60 seconds.")));
    }

    private ResultActions login(String ip) throws Exception {
        return mockMvc.perform(loginRequest(ip));
    }

    private MockHttpServletRequestBuilder loginRequest(String ip) {
        return fromIp(post("/api/auth/login"), ip)
                .header(HttpHeaders.ORIGIN, ORIGIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(BAD_LOGIN);
    }

    private ResultActions socialCodeExchange(String ip) throws Exception {
        return mockMvc.perform(fromIp(post("/api/auth/kakao"), ip)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\": \"c\", \"redirectUri\": \"http://localhost:5173\"}"));
    }

    /** 빈 요청이라 인증·레이트 리밋을 통과하면 검증 400 이 난다. */
    private ResultActions sync(String userId) throws Exception {
        return mockMvc.perform(post("/api/health-activities/sync")
                .with(bearer(tokenService, userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"));
    }
}
