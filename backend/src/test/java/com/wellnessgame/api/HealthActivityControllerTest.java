package com.wellnessgame.api;

import com.wellnessgame.activity.HealthActivityRepository;
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

import java.util.Collections;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestClockConfig.class)
class HealthActivityControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private HealthActivityRepository activityRepository;

    @Autowired
    private UserCharacterRepository characterRepository;

    @Autowired
    private MutableClock clock;

    @BeforeEach
    void cleanDatabase() {
        activityRepository.deleteAll();
        characterRepository.deleteAll();
        clock.setInstant(TestClockConfig.DEFAULT_NOW);
    }

    @Test
    void syncsHealthActivities() throws Exception {
        postSync(syncBody("2026-06-17", STEPS_8500))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gainedXp", is(70)))
                .andExpect(jsonPath("$.character.level", is(1)))
                .andExpect(jsonPath("$.activityResults[0].message", is("걸음 수 보상 +70 XP")))
                .andExpect(jsonPath("$.goals", hasSize(3)));
    }

    @Test
    void listsDailyActivitiesSyncedFromApp() throws Exception {
        postSync(syncBody("2026-06-17", STEPS_8500, """
                {
                  "type": "WORKOUT",
                  "workoutType": "RUNNING",
                  "durationMinutes": 30,
                  "calories": 250,
                  "startedAt": "2026-06-17T08:00:00Z",
                  "endedAt": "2026-06-17T08:30:00Z"
                }
                """))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/health-activities")
                        .param("userId", "test-user")
                        .param("date", "2026-06-17"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId", is("test-user")))
                .andExpect(jsonPath("$.date", is("2026-06-17")))
                .andExpect(jsonPath("$.activities", hasSize(2)))
                .andExpect(jsonPath("$.activities[0].type", is("STEPS")))
                .andExpect(jsonPath("$.activities[0].steps", is(8500)))
                .andExpect(jsonPath("$.activities[0].gainedXp", is(70)))
                .andExpect(jsonPath("$.activities[1].type", is("WORKOUT")))
                .andExpect(jsonPath("$.activities[1].source", is("RUNNING")))
                .andExpect(jsonPath("$.activities[1].durationMinutes", is(30)));
    }

    @Test
    void returnsEmptyActivityListWhenNothingSynced() throws Exception {
        mockMvc.perform(get("/api/health-activities")
                        .param("userId", "test-user")
                        .param("date", "2026-06-17"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activities", hasSize(0)));
    }

    @Test
    void rejectsIncompleteWorkoutPayload() throws Exception {
        postSync(syncBody("2026-06-17", """
                {"type": "WORKOUT", "workoutType": "RUNNING", "durationMinutes": 20}
                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", is("WORKOUT 활동에는 startedAt과 endedAt이 필요합니다.")));
    }

    @Test
    void rejectsWorkoutThatEndsBeforeItStarts() throws Exception {
        postSync(syncBody("2026-06-17", """
                {
                  "type": "WORKOUT",
                  "workoutType": "RUNNING",
                  "durationMinutes": 20,
                  "startedAt": "2026-06-17T08:30:00Z",
                  "endedAt": "2026-06-17T08:00:00Z"
                }
                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", is("활동 종료 시간은 시작 시간 이후여야 합니다.")));
    }

    @Test
    void rejectsMoreThanFiftyActivities() throws Exception {
        postSync(syncBody("2026-06-17", Collections.nCopies(51, STEPS_1000).toArray(String[]::new)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", is("activities: 한 번에 최대 50개의 활동까지 동기화할 수 있습니다.")));
    }

    @Test
    void acceptsExactlyFiftyActivities() throws Exception {
        postSync(syncBody("2026-06-17", Collections.nCopies(50, STEPS_1000).toArray(String[]::new)))
                .andExpect(status().isOk());
    }

    @Test
    void rejectsSyncDateOutsideServerTodayWindow() throws Exception {
        postSync(syncBody("2026-06-19", STEPS_8500))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", is("동기화 날짜는 오늘 기준 ±1일 이내여야 합니다.")));
    }

    @Test
    void valueViolationIsReportedPerItemWithOk() throws Exception {
        postSync(syncBody("2026-06-17", STEPS_8500, """
                {"type": "STEPS", "steps": 150000}
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gainedXp", is(70)))
                .andExpect(jsonPath("$.activityResults[1].duplicate", is(true)))
                .andExpect(jsonPath("$.activityResults[1].message",
                        is("STEPS 기록을 반영하지 않았어요: 걸음 수는 0 이상 100000 이하여야 합니다.")));
    }

    /**
     * iOS(AppleHealthKitProvider + JSONEncoder .iso8601) 가 실제로 보내는 형태 계약:
     * 병합된 수면 구간(구간 길이 455분 > 실제 수면 431분), 반올림된 운동 분(30분 40초 → 31분),
     * Double 칼로리/거리, 오늘 누적 걸음 수. 정상 요청은 하나도 건너뛰지 않아야 한다.
     */
    @Test
    void acceptsRealisticIosPayload() throws Exception {
        postSync(syncBody("2026-06-17",
                """
                {"type": "STEPS", "steps": 8732}
                """,
                """
                {
                  "type": "WORKOUT",
                  "workoutType": "RUNNING",
                  "durationMinutes": 31,
                  "calories": 287.6,
                  "distanceMeters": 5012.3,
                  "startedAt": "2026-06-17T07:00:00Z",
                  "endedAt": "2026-06-17T07:30:40Z"
                }
                """,
                """
                {
                  "type": "SLEEP",
                  "sleepMinutes": 431,
                  "sleepScore": 90,
                  "startedAt": "2026-06-16T14:10:00Z",
                  "endedAt": "2026-06-16T21:45:00Z"
                }
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activityResults[*].duplicate", everyItem(is(false))))
                .andExpect(jsonPath("$.activityResults[*].gainedXp", contains(70, 136, 80))) // 136 = 31*3 + 28 + 15
                .andExpect(jsonPath("$.gainedXp", is(286)));
    }

    // ---- helpers ----

    private static final String STEPS_8500 = "{\"type\": \"STEPS\", \"steps\": 8500}";
    private static final String STEPS_1000 = "{\"type\": \"STEPS\", \"steps\": 1000}";

    private ResultActions postSync(String body) throws Exception {
        return mockMvc.perform(post("/api/health-activities/sync")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private static String syncBody(String date, String... activities) {
        return "{\"userId\": \"test-user\", \"date\": \"" + date + "\", \"activities\": ["
                + String.join(",", activities) + "]}";
    }
}
