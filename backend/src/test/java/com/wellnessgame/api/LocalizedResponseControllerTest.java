package com.wellnessgame.api;

import com.wellnessgame.activity.HealthActivityRepository;
import com.wellnessgame.auth.JwtTokenService;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.Collections;

import static com.wellnessgame.support.TestAuth.bearer;
import static com.wellnessgame.support.TestAuth.bearerToken;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Accept-Language 에 따른 응답 문구(#52). en 이면 영어, 헤더가 없거나 지원하지 않는 언어면 ko.
 * 응답 구조와 점수는 언어와 무관해야 한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestClockConfig.class)
class LocalizedResponseControllerTest {
    private static final String STEPS_8500 = "{\"type\": \"STEPS\", \"steps\": 8500}";
    private static final String STEPS_TOO_MANY = "{\"type\": \"STEPS\", \"steps\": 150000}";
    private static final String RUNNING = """
            {
              "type": "WORKOUT",
              "workoutType": "RUNNING",
              "durationMinutes": 30,
              "startedAt": "2026-06-17T08:00:00Z",
              "endedAt": "2026-06-17T08:30:00Z"
            }
            """;
    private static final String CYCLING_OVERLAPPING = """
            {
              "type": "WORKOUT",
              "workoutType": "CYCLING",
              "durationMinutes": 20,
              "startedAt": "2026-06-17T08:10:00Z",
              "endedAt": "2026-06-17T08:30:00Z"
            }
            """;
    private static final String SLEEP = """
            {
              "type": "SLEEP",
              "sleepMinutes": 431,
              "sleepScore": 90,
              "startedAt": "2026-06-16T14:10:00Z",
              "endedAt": "2026-06-16T21:45:00Z"
            }
            """;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private HealthActivityRepository activityRepository;

    @Autowired
    private UserCharacterRepository characterRepository;

    @Autowired
    private MutableClock clock;

    @Autowired
    private JwtTokenService tokenService;

    @BeforeEach
    void cleanDatabase() {
        activityRepository.deleteAll();
        characterRepository.deleteAll();
        clock.setInstant(TestClockConfig.DEFAULT_NOW);
    }

    // ---- 활동 결과 메시지 ----

    @Test
    void activityResultMessagesAreEnglish() throws Exception {
        postSync("en", syncBody(STEPS_8500, RUNNING, CYCLING_OVERLAPPING, SLEEP, STEPS_TOO_MANY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gainedXp", is(70 + 105 + 80)))
                .andExpect(jsonPath("$.activityResults[0].message", is("Step reward +70 XP")))
                .andExpect(jsonPath("$.activityResults[1].message", is("Running complete +105 XP")))
                .andExpect(jsonPath("$.activityResults[2].message",
                        is("CYCLING overlaps a workout that’s already counted")))
                .andExpect(jsonPath("$.activityResults[3].message", is("Sleep recovery bonus +80 XP")))
                .andExpect(jsonPath("$.activityResults[4].message",
                        is("STEPS wasn’t counted: Steps must be between 0 and 100,000.")))
                .andExpect(jsonPath("$.activityResults[4].source", is("STEPS")))
                .andExpect(jsonPath("$.goals[0].unit", is("steps")));
    }

    @Test
    void duplicateMessageIsEnglish() throws Exception {
        postSync("en", syncBody(SLEEP)).andExpect(status().isOk());

        postSync("en", syncBody(SLEEP))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activityResults[0].message", is("SLEEP already counted")))
                .andExpect(jsonPath("$.activityResults[0].duplicate", is(true)));
    }

    @Test
    void activityResultMessagesFallBackToKoreanWithoutHeaderOrForUnsupportedLanguage() throws Exception {
        postSync(null, syncBody(STEPS_8500))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activityResults[0].message", is("걸음 수 보상 +70 XP")));

        postSync("fr", syncBody(RUNNING, STEPS_TOO_MANY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activityResults[0].message", is("달리기 완료 +105 XP")))
                .andExpect(jsonPath("$.activityResults[1].message",
                        is("STEPS 기록을 반영하지 않았어요: 걸음 수는 0 이상 100000 이하여야 합니다.")));
    }

    @Test
    void regionalAndWeightedEnglishHeadersResolveToEnglish() throws Exception {
        postSync("en-US,en;q=0.9", syncBody(STEPS_8500))
                .andExpect(jsonPath("$.activityResults[0].message", is("Step reward +70 XP")));

        postSync("fr-FR, en;q=0.5", syncBody(RUNNING))
                .andExpect(jsonPath("$.activityResults[0].message", is("Running complete +105 XP")));
    }

    // ---- 검증 에러 ----

    @Test
    void structuralValidationErrorIsEnglish() throws Exception {
        postSync("en", syncBody("""
                {"type": "WORKOUT", "workoutType": "RUNNING", "durationMinutes": 20}
                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", is("WORKOUT activities need startedAt and endedAt.")));

        postSync("en", "{\"date\": \"2026-06-19\", \"activities\": [" + STEPS_8500 + "]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", is("The sync date must be within 1 day of today.")));
    }

    @Test
    void beanValidationErrorsAreEnglish() throws Exception {
        postSync("en", syncBody(Collections.nCopies(51, STEPS_8500).toArray(String[]::new)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", is("activities: You can sync up to 50 activities at a time.")));

        postSync("en", "{\"activities\": [" + STEPS_8500 + "]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", is("date: is required.")));

        postJson("en", "/api/auth/signup", """
                {"username": "ab", "password": "password1"}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message",
                        is("username: Username must be 4–32 characters using letters, numbers, or underscores.")));
    }

    @Test
    void validationErrorsFallBackToKorean() throws Exception {
        postSync("fr", syncBody(Collections.nCopies(51, STEPS_8500).toArray(String[]::new)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", is("activities: 한 번에 최대 50개의 활동까지 동기화할 수 있습니다.")));

        postSync(null, syncBody("""
                {"type": "WORKOUT", "workoutType": "RUNNING", "durationMinutes": 20}
                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", is("WORKOUT 활동에는 startedAt과 endedAt이 필요합니다.")));
    }

    // ---- 인증·인가 ----

    @Test
    void unauthorizedMessagesAreEnglish() throws Exception {
        mockMvc.perform(get("/api/health-activities")
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "en")
                        .param("date", "2026-06-17"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message", is("Please sign in to continue.")));

        mockMvc.perform(get("/api/health-activities")
                        .with(bearerToken("not-a-jwt"))
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "en")
                        .param("date", "2026-06-17"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message", is("Your session is invalid. Please sign in again.")));
    }

    @Test
    void unauthorizedMessageFallsBackToKorean() throws Exception {
        mockMvc.perform(get("/api/health-activities")
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "fr")
                        .param("date", "2026-06-17"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message", is("인증이 필요합니다. 로그인해 주세요.")));

        mockMvc.perform(get("/api/health-activities").param("date", "2026-06-17"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message", is("인증이 필요합니다. 로그인해 주세요.")));
    }

    @Test
    void forbiddenMessageIsEnglish() throws Exception {
        mockMvc.perform(get("/api/health-activities")
                        .with(bearer(tokenService, "google:intruder"))
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "en")
                        .param("userId", "test-user")
                        .param("date", "2026-06-17"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message", is("You can’t access another user’s data.")));
    }

    @Test
    void loginErrorsAreEnglish() throws Exception {
        postJson("en", "/api/auth/signup", """
                {"username": "i18n_user", "password": "password1"}
                """).andExpect(status().isOk());

        postJson("en", "/api/auth/signup", """
                {"username": "i18n_user", "password": "password1"}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", is("That username is already taken.")));

        postJson("en", "/api/auth/login", """
                {"username": "i18n_user", "password": "wrong-password"}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", is("Incorrect username or password.")));

        postJson("en", "/api/auth/unknown", """
                {"code": "c", "redirectUri": "https://example.com"}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", is("Unsupported sign-in provider: unknown")));
    }

    // ---- helpers ----

    private ResultActions postSync(String acceptLanguage, String body) throws Exception {
        return mockMvc.perform(withLanguage(post("/api/health-activities/sync"), acceptLanguage)
                .with(bearer(tokenService, "test-user"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions postJson(String acceptLanguage, String path, String body) throws Exception {
        return mockMvc.perform(withLanguage(post(path), acceptLanguage)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private static MockHttpServletRequestBuilder withLanguage(MockHttpServletRequestBuilder builder, String language) {
        return language == null ? builder : builder.header(HttpHeaders.ACCEPT_LANGUAGE, language);
    }

    private static String syncBody(String... activities) {
        return "{\"userId\": \"test-user\", \"date\": \"2026-06-17\", \"activities\": ["
                + String.join(",", activities) + "]}";
    }
}
