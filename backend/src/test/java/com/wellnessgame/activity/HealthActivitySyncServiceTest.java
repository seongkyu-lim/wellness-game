package com.wellnessgame.activity;

import com.wellnessgame.api.HealthActivitySyncRequest;
import com.wellnessgame.api.HealthActivitySyncRequest.ActivityPayload;
import com.wellnessgame.api.HealthActivitySyncResponse;
import com.wellnessgame.character.UserCharacterRepository;
import com.wellnessgame.support.ActivityPayloads;
import com.wellnessgame.support.MutableClock;
import com.wellnessgame.support.TestClockConfig;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static com.wellnessgame.support.ActivityPayloads.sleep;
import static com.wellnessgame.support.ActivityPayloads.steps;
import static com.wellnessgame.support.ActivityPayloads.workout;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(TestClockConfig.class)
class HealthActivitySyncServiceTest {
    @Autowired
    private HealthActivitySyncService syncService;

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
    void preventsDuplicateXpAndStatRewards() {
        HealthActivitySyncRequest request = new HealthActivitySyncRequest(
                "test-user",
                LocalDate.of(2026, 6, 17),
                List.of(workout(WorkoutType.RUNNING, 30, "2026-06-17T08:00:00Z", "2026-06-17T08:30:00Z"))
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
                        steps(16_000),
                        sleep(450, 90, "2026-06-16T22:30:00Z", "2026-06-17T06:00:00Z")
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
                        steps(8_500),
                        workout(WorkoutType.RUNNING, 20, "2026-06-17T08:00:00Z", "2026-06-17T08:20:00Z")
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
                    List.of(steps(2_000))
            ));

            assertThat(response.userId()).isEqualTo(userId);
            assertThat(response.gainedXp()).isEqualTo(10);
        }

        assertThat(characterRepository.count()).isEqualTo(5);
        assertThat(activityRepository.count()).isEqualTo(5);
    }

    private static final String USER = "steps-user";
    private static final LocalDate DAY = LocalDate.of(2026, 6, 20);
    private static final Instant DAY_NOW = Instant.parse("2026-06-20T23:00:00Z");

    private HealthActivitySyncResponse syncSteps(int... stepsValues) {
        clock.setInstant(DAY_NOW);
        List<ActivityPayload> payloads = java.util.Arrays.stream(stepsValues)
                .mapToObj(ActivityPayloads::steps)
                .toList();
        return syncService.sync(new HealthActivitySyncRequest(USER, DAY, payloads));
    }

    private static HealthActivitySyncResponse.GoalResponse stepsGoal(HealthActivitySyncResponse response) {
        return response.goals().stream()
                .filter(goal -> goal.type() == ActivityType.STEPS)
                .findFirst()
                .orElseThrow();
    }

    @Test
    void resyncWithMoreStepsGrantsOnlyXpDifferenceAndGoalBonusOnce() {
        HealthActivitySyncResponse morning = syncSteps(3_000);
        HealthActivitySyncResponse evening = syncSteps(9_000);

        assertThat(morning.gainedXp()).isEqualTo(15);              // 3*5
        assertThat(morning.character().stats().discipline()).isEqualTo(1);

        assertThat(evening.gainedXp()).isEqualTo(60);              // 75(9*5+30) - 15
        assertThat(evening.activityResults()).singleElement().satisfies(result -> {
            assertThat(result.duplicate()).isFalse();
            assertThat(result.gainedXp()).isEqualTo(60);
        });
        assertThat(evening.character().totalXp()).isEqualTo(75);
        assertThat(evening.character().stats().discipline()).isEqualTo(2); // 기본 +1, 목표 +1
        assertThat(stepsGoal(evening).current()).isEqualTo(9_000);
        assertThat(stepsGoal(evening).achieved()).isTrue();

        assertThat(activityRepository.count()).isEqualTo(1);
        HealthActivity log = activityRepository.findAll().get(0);
        assertThat(log.getSteps()).isEqualTo(9_000);
        assertThat(log.getGainedXp()).isEqualTo(75);

        HealthActivitySyncResponse later = syncSteps(12_000);
        assertThat(later.gainedXp()).isEqualTo(15);                // 90 - 75
        assertThat(later.character().stats().discipline()).isEqualTo(2); // 보너스 재지급 없음
    }

    @Test
    void resyncWithSameStepsIsDuplicate() {
        syncSteps(5_000);
        HealthActivitySyncResponse again = syncSteps(5_000);

        assertThat(again.gainedXp()).isZero();
        assertThat(again.activityResults()).singleElement().satisfies(result -> {
            assertThat(result.duplicate()).isTrue();
            assertThat(result.gainedXp()).isZero();
        });
        assertThat(again.character().totalXp()).isEqualTo(25);
        assertThat(again.character().stats().discipline()).isEqualTo(1);
    }

    @Test
    void resyncWithFewerStepsChangesNothing() {
        syncSteps(9_000);
        HealthActivitySyncResponse lower = syncSteps(4_000);

        assertThat(lower.gainedXp()).isZero();
        assertThat(lower.activityResults().get(0).duplicate()).isTrue();
        assertThat(lower.character().totalXp()).isEqualTo(75);
        assertThat(lower.character().stats().discipline()).isEqualTo(2);
        assertThat(stepsGoal(lower).current()).isEqualTo(9_000);
        assertThat(activityRepository.findAll().get(0).getSteps()).isEqualTo(9_000);
    }

    @Test
    void resyncWithinSameThousandStepBandUpdatesLogWithZeroXp() {
        syncSteps(5_000);
        HealthActivitySyncResponse more = syncSteps(5_500);

        assertThat(more.gainedXp()).isZero();
        assertThat(more.activityResults()).singleElement().satisfies(result -> {
            assertThat(result.duplicate()).isFalse();
            assertThat(result.gainedXp()).isZero();
        });
        assertThat(stepsGoal(more).current()).isEqualTo(5_500);
        assertThat(activityRepository.findAll().get(0).getSteps()).isEqualTo(5_500);
        assertThat(activityRepository.findAll().get(0).getGainedXp()).isEqualTo(25);
    }

    @Test
    void resyncAfterXpCapUpdatesLogWithZeroXp() {
        syncSteps(50_000);
        HealthActivitySyncResponse more = syncSteps(60_000);

        assertThat(more.gainedXp()).isZero();
        assertThat(more.activityResults().get(0).duplicate()).isFalse();
        assertThat(more.character().totalXp()).isEqualTo(100);
        assertThat(more.character().stats().discipline()).isEqualTo(2);
        assertThat(activityRepository.findAll().get(0).getSteps()).isEqualTo(60_000);
    }

    @Test
    void firstSyncBelowThousandStepsIsNotReportedAsDuplicate() {
        HealthActivitySyncResponse first = syncSteps(500);

        assertThat(first.gainedXp()).isZero();
        assertThat(first.activityResults().get(0).duplicate()).isFalse();
        assertThat(first.character().stats().discipline()).isEqualTo(1);
    }

    @Test
    void stepsXpDifferenceCanLevelUp() {
        syncSteps(3_000);   // 15 XP, Lv.1 (다음 레벨 100)
        HealthActivitySyncResponse evening = syncSteps(20_000); // 100 XP 상한 → 차액 85

        assertThat(evening.gainedXp()).isEqualTo(85);
        assertThat(evening.levelUp()).isTrue();
        assertThat(evening.character().totalXp()).isEqualTo(100);
    }

    @Test
    void goalBoundaryCrossingFrom7999To8000GrantsBonus() {
        HealthActivitySyncResponse below = syncSteps(7_999);
        assertThat(below.gainedXp()).isEqualTo(35);                // 7*5
        assertThat(below.character().stats().discipline()).isEqualTo(1);
        assertThat(stepsGoal(below).achieved()).isFalse();

        HealthActivitySyncResponse reached = syncSteps(8_000);
        assertThat(reached.gainedXp()).isEqualTo(35);              // 70(8*5+30) - 35
        assertThat(reached.character().stats().discipline()).isEqualTo(2);
        assertThat(stepsGoal(reached).current()).isEqualTo(8_000);
        assertThat(stepsGoal(reached).achieved()).isTrue();
    }

    @Test
    void firstSyncAtExactlyGoalGrantsBaseAndGoalDiscipline() {
        HealthActivitySyncResponse response = syncSteps(8_000);

        assertThat(response.gainedXp()).isEqualTo(70);
        assertThat(response.character().stats().discipline()).isEqualTo(2);
    }

    @Test
    void multipleStepsInOneRequestApplyOnlyTheLargest() {
        HealthActivitySyncResponse response = syncSteps(3_000, 9_000, 5_000);

        assertThat(response.gainedXp()).isEqualTo(75);
        assertThat(response.activityResults()).extracting(r -> r.duplicate())
                .containsExactly(true, false, true);
        assertThat(response.character().stats().discipline()).isEqualTo(2);
        assertThat(activityRepository.count()).isEqualTo(1);
        assertThat(stepsGoal(response).current()).isEqualTo(9_000);
    }

    @Test
    void goalsReflectAccumulatedDatabaseValuesNotRequestPayload() {
        clock.setInstant(DAY_NOW);
        syncService.sync(new HealthActivitySyncRequest(USER, DAY, List.of(
                steps(9_000),
                workout(WorkoutType.RUNNING, 20, "2026-06-20T08:00:00Z", "2026-06-20T08:20:00Z"),
                sleep(450, 90, "2026-06-19T22:30:00Z", "2026-06-20T06:00:00Z")
        )));

        // 두 번째 요청에는 운동 15분만 포함 — goals 는 DB 누적값 기준이어야 한다.
        HealthActivitySyncResponse second = syncService.sync(new HealthActivitySyncRequest(USER, DAY, List.of(
                workout(WorkoutType.CYCLING, 15, "2026-06-20T18:00:00Z", "2026-06-20T18:15:00Z")
        )));

        assertThat(second.goals())
                .anySatisfy(goal -> {
                    assertThat(goal.type()).isEqualTo(ActivityType.STEPS);
                    assertThat(goal.current()).isEqualTo(9_000);
                    assertThat(goal.achieved()).isTrue();
                })
                .anySatisfy(goal -> {
                    assertThat(goal.type()).isEqualTo(ActivityType.WORKOUT);
                    assertThat(goal.current()).isEqualTo(35);
                    assertThat(goal.achieved()).isTrue();
                })
                .anySatisfy(goal -> {
                    assertThat(goal.type()).isEqualTo(ActivityType.SLEEP);
                    assertThat(goal.current()).isEqualTo(450);
                    assertThat(goal.achieved()).isTrue();
                });
    }
}
