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

import static com.wellnessgame.support.TestAuth.bearer;
import static com.wellnessgame.support.TestRequests.fromIp;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 429 응답의 영어 문구, Retry-After 올림 경계, 경로별 한도 분리. 테스트마다 서로 다른 IP·사용자를 쓴다. */
@SpringBootTest(properties = {
        "app.rate-limit.sync-per-minute=1",
        "app.rate-limit.auth-per-minute=3",
        "app.rate-limit.login-per-minute=1",
        "app.rate-limit.refresh-per-minute=2"
})
@AutoConfigureMockMvc
@Import(TestClockConfig.class)
class RateLimitLocalizedResponseTest {
    private static final String BAD_LOGIN = "{\"username\":\"nobody_loc\",\"password\":\"wrong-password\"}";
    private static final String SYNC = "{\"date\":\"2026-06-17\",\"activities\":[{\"type\":\"STEPS\",\"steps\":1000}]}";

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

    private ResultActions login(String ip, String language) throws Exception {
        return mockMvc.perform(fromIp(post("/api/auth/login"), ip)
                .header(HttpHeaders.ACCEPT_LANGUAGE, language)
                .contentType(MediaType.APPLICATION_JSON).content(BAD_LOGIN));
    }

    @Test
    void englishMessageAndPluralFormFollowRemainingSeconds() throws Exception {
        login("203.0.113.10", "en").andExpect(status().isBadRequest());

        login("203.0.113.10", "en")
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "60"))
                .andExpect(jsonPath("$.message", is("Too many requests. Please try again in 60 seconds.")));

        // 창 끝 1초 전: 단수형, Retry-After 1
        clock.setInstant(TestClockConfig.DEFAULT_NOW.plusSeconds(59));
        login("203.0.113.10", "en")
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "1"))
                .andExpect(jsonPath("$.message", is("Too many requests. Please try again in 1 second.")));
    }

    @Test
    void limitResetsExactlyWhenTheWindowEnds() throws Exception {
        login("203.0.113.11", "ko").andExpect(status().isBadRequest());
        clock.setInstant(TestClockConfig.DEFAULT_NOW.plusMillis(59_999));
        login("203.0.113.11", "ko").andExpect(status().isTooManyRequests());

        clock.setInstant(TestClockConfig.DEFAULT_NOW.plusSeconds(60));
        login("203.0.113.11", "ko").andExpect(status().isBadRequest());
    }

    @Test
    void loginLimitIsIndependentOfRefreshLimit() throws Exception {
        String body = "{\"refreshToken\":\"" + "r".repeat(43) + "\"}";
        // login 한도(1)가 소진돼도 refresh 는 자기 한도(2)를 따로 센다
        login("203.0.113.12", "ko").andExpect(status().isBadRequest());
        login("203.0.113.12", "ko").andExpect(status().isTooManyRequests());

        for (int i = 0; i < 2; i++) {
            mockMvc.perform(fromIp(post("/api/auth/refresh"), "203.0.113.12")
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isUnauthorized());
        }
        mockMvc.perform(fromIp(post("/api/auth/refresh"), "203.0.113.12")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void syncIsLimitedPerUserNotPerIp() throws Exception {
        String user1 = "limited-user-1";
        String user2 = "limited-user-2";
        mockMvc.perform(fromIp(post("/api/health-activities/sync"), "203.0.113.13").with(bearer(tokenService, user1))
                        .contentType(MediaType.APPLICATION_JSON).content(SYNC))
                .andExpect(status().isOk());

        mockMvc.perform(fromIp(post("/api/health-activities/sync"), "203.0.113.13").with(bearer(tokenService, user1))
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "en")
                        .contentType(MediaType.APPLICATION_JSON).content(SYNC))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message", is("Too many requests. Please try again in 60 seconds.")));
        // 같은 IP 의 다른 사용자는 영향이 없다
        mockMvc.perform(fromIp(post("/api/health-activities/sync"), "203.0.113.13").with(bearer(tokenService, user2))
                        .contentType(MediaType.APPLICATION_JSON).content(SYNC))
                .andExpect(status().isOk());
    }

    @Test
    void unauthenticatedSyncIsRejectedBeforeItIsCounted() throws Exception {
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(fromIp(post("/api/health-activities/sync"), "203.0.113.14")
                            .contentType(MediaType.APPLICATION_JSON).content(SYNC))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Test
    void corsPreflightIsNeverCounted() throws Exception {
        for (int i = 0; i < 6; i++) {
            mockMvc.perform(fromIp(options("/api/auth/login"), "203.0.113.15")
                            .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                    .andExpect(status().isOk());
        }
        login("203.0.113.15", "ko").andExpect(status().isBadRequest());
    }
}
