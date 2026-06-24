package com.wellnessgame.api;

import com.wellnessgame.activity.HealthActivityRepository;
import com.wellnessgame.character.UserCharacterRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class HealthActivityControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private HealthActivityRepository activityRepository;

    @Autowired
    private UserCharacterRepository characterRepository;

    @BeforeEach
    void cleanDatabase() {
        activityRepository.deleteAll();
        characterRepository.deleteAll();
    }

    @Test
    void syncsHealthActivities() throws Exception {
        mockMvc.perform(post("/api/health-activities/sync")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "userId": "test-user",
                                  "date": "2026-06-17",
                                  "activities": [
                                    {"type": "STEPS", "steps": 8500}
                                  ]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gainedXp", is(42)))
                .andExpect(jsonPath("$.character.level", is(1)))
                .andExpect(jsonPath("$.activityResults[0].message", is("걸음 수 보상 +42 XP")));
    }

    @Test
    void rejectsIncompleteWorkoutPayload() throws Exception {
        mockMvc.perform(post("/api/health-activities/sync")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "userId": "test-user",
                                  "date": "2026-06-17",
                                  "activities": [
                                    {"type": "WORKOUT", "workoutType": "RUNNING", "durationMinutes": 20}
                                  ]
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", is("WORKOUT 활동에는 startedAt과 endedAt이 필요합니다.")));
    }

    @Test
    void rejectsWorkoutThatEndsBeforeItStarts() throws Exception {
        mockMvc.perform(post("/api/health-activities/sync")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "userId": "test-user",
                                  "date": "2026-06-17",
                                  "activities": [
                                    {
                                      "type": "WORKOUT",
                                      "workoutType": "RUNNING",
                                      "durationMinutes": 20,
                                      "startedAt": "2026-06-17T08:30:00Z",
                                      "endedAt": "2026-06-17T08:00:00Z"
                                    }
                                  ]
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", is("활동 종료 시간은 시작 시간 이후여야 합니다.")));
    }
}
