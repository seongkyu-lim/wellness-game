package com.wellnessgame.activity;

import com.wellnessgame.api.HealthActivitySyncRequest;
import com.wellnessgame.api.HealthActivitySyncRequest.ActivityPayload;
import com.wellnessgame.api.HealthActivitySyncResponse;
import com.wellnessgame.character.UserCharacterRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class HealthActivitySyncServiceTest {
    @Autowired
    private HealthActivitySyncService syncService;

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
    void preventsDuplicateXpAndStatRewards() {
        HealthActivitySyncRequest request = new HealthActivitySyncRequest(
                "test-user",
                LocalDate.of(2026, 6, 17),
                List.of(new ActivityPayload(
                        ActivityType.WORKOUT,
                        WorkoutType.RUNNING,
                        30,
                        null,
                        null,
                        null,
                        null,
                        null,
                        Instant.parse("2026-06-17T08:00:00Z"),
                        Instant.parse("2026-06-17T08:30:00Z")
                ))
        );

        HealthActivitySyncResponse first = syncService.sync(request);
        HealthActivitySyncResponse second = syncService.sync(request);

        assertThat(first.gainedXp()).isEqualTo(105); // 30*3 + 15(RUNNING)
        assertThat(second.gainedXp()).isZero();
        assertThat(second.activityResults().get(0).duplicate()).isTrue();
        assertThat(second.character().totalXp()).isEqualTo(105);
        assertThat(second.character().stats().str()).isEqualTo(1); // 유산소: STR +1
        assertThat(second.character().stats().vit()).isEqualTo(2); // 유산소: VIT +2
        assertThat(activityRepository.count()).isEqualTo(1);
    }

    @Test
    void handlesSeveralActivitiesAndLevelsUp() {
        HealthActivitySyncRequest request = new HealthActivitySyncRequest(
                "test-user",
                LocalDate.of(2026, 6, 17),
                List.of(
                        new ActivityPayload(ActivityType.STEPS, null, null, null, null, 16_000,
                                null, null, null, null),
                        new ActivityPayload(ActivityType.SLEEP, null, null, null, null, null,
                                450, 90,
                                Instant.parse("2026-06-16T22:30:00Z"),
                                Instant.parse("2026-06-17T06:00:00Z"))
                )
        );

        HealthActivitySyncResponse response = syncService.sync(request);

        assertThat(response.gainedXp()).isEqualTo(180); // STEPS 100(상한) + SLEEP 80
        assertThat(response.levelUp()).isTrue();
        assertThat(response.character().level()).isEqualTo(2);
        assertThat(response.character().currentXp()).isEqualTo(80);   // 180 - 100
        assertThat(response.character().stats().discipline()).isEqualTo(2); // 걸음 +1, 목표 달성 +1
        assertThat(response.character().stats().recovery()).isEqualTo(1);
        assertThat(response.character().stats().intStat()).isEqualTo(2); // 수면 품질 90 → INT +2
    }

    @Test
    void reportsDailyGoalStatus() {
        HealthActivitySyncResponse response = syncService.sync(new HealthActivitySyncRequest(
                "test-user",
                LocalDate.of(2026, 6, 17),
                List.of(
                        new ActivityPayload(ActivityType.STEPS, null, null, null, null, 8_500,
                                null, null, null, null),
                        new ActivityPayload(ActivityType.WORKOUT, WorkoutType.RUNNING, 20,
                                null, null, null, null, null,
                                Instant.parse("2026-06-17T08:00:00Z"),
                                Instant.parse("2026-06-17T08:20:00Z"))
                )
        ));

        assertThat(response.goals()).hasSize(3);
        assertThat(response.goals())
                .anySatisfy(goal -> {
                    assertThat(goal.type()).isEqualTo(ActivityType.STEPS);
                    assertThat(goal.current()).isEqualTo(8_500);
                    assertThat(goal.achieved()).isTrue();
                })
                .anySatisfy(goal -> {
                    assertThat(goal.type()).isEqualTo(ActivityType.WORKOUT);
                    assertThat(goal.current()).isEqualTo(20);
                    assertThat(goal.achieved()).isFalse(); // 목표 30분 미달
                })
                .anySatisfy(goal -> {
                    assertThat(goal.type()).isEqualTo(ActivityType.SLEEP);
                    assertThat(goal.current()).isZero();
                    assertThat(goal.achieved()).isFalse();
                });
    }

    @Test
    void keepsGuestAndSocialProviderProgressSeparate() {
        List<String> userIds = List.of(
                "guest:device-123",
                "apple:apple-user-123",
                "google:google-user-123",
                "kakao:987654321",
                "naver:naver-user-123"
        );

        for (String userId : userIds) {
            HealthActivitySyncResponse response = syncService.sync(new HealthActivitySyncRequest(
                    userId,
                    LocalDate.of(2026, 6, 18),
                    List.of(new ActivityPayload(
                            ActivityType.STEPS,
                            null,
                            null,
                            null,
                            null,
                            2_000,
                            null,
                            null,
                            null,
                            null
                    ))
            ));

            assertThat(response.userId()).isEqualTo(userId);
            assertThat(response.gainedXp()).isEqualTo(10);
        }

        assertThat(characterRepository.count()).isEqualTo(5);
        assertThat(activityRepository.count()).isEqualTo(5);
    }
}
