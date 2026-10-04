package com.wellnessgame.activity;

import com.wellnessgame.api.HealthActivitySyncRequest;
import com.wellnessgame.api.HealthActivitySyncRequest.ActivityPayload;
import com.wellnessgame.api.HealthActivitySyncResponse;
import com.wellnessgame.character.UserCharacterRepository;
import com.wellnessgame.support.MutableClock;
import com.wellnessgame.support.TestClockConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * #42 동기화 규칙: 구간 겹침 거부, 일일 XP 상한, goals 합산 포화.
 */
@SpringBootTest
@Import(TestClockConfig.class)
class HealthActivitySyncRulesTest {
    private static final String USER = "rules-user";
    private static final LocalDate DAY = LocalDate.of(2026, 6, 17);

    @Autowired
    private HealthActivitySyncService syncService;

    @Autowired
    private HealthActivityRepository activityRepository;

    @Autowired
    private UserCharacterRepository characterRepository;

    @Autowired
    private MutableClock clock;

    @BeforeEach
    void setUp() {
        activityRepository.deleteAll();
        characterRepository.deleteAll();
        clock.setInstant(Instant.parse("2026-06-17T23:00:00Z"));
    }

    // ---- 겹침 ----

    @Test
    void skipsOnlyWorkoutOverlappingStoredWorkoutAndAppliesTheRest() {
        sync(workout(WorkoutType.RUNNING, 30, "08:00", "08:30"));

        HealthActivitySyncResponse response = sync(
                steps(8_500),
                workout(WorkoutType.CYCLING, 31, "08:29", "09:00"),
                workout(WorkoutType.WALKING, 30, "10:00", "10:30"));

        assertThat(response.activityResults()).extracting(r -> r.duplicate())
                .containsExactly(false, true, false);
        assertThat(response.activityResults().get(1).gainedXp()).isZero();
        assertThat(response.activityResults().get(1).message()).isEqualTo("CYCLING 겹치는 기록이 이미 반영됨");
        assertThat(response.gainedXp()).isEqualTo(70 + 95); // 걸음 70 + 걷기 90+5
        assertThat(response.character().stats().vit()).isEqualTo(4); // 겹친 자전거는 스탯도 미반영
        assertThat(activityRepository.count()).isEqualTo(3);
    }

    @Test
    void acceptsWorkoutThatOnlyTouchesStoredWorkoutBoundary() {
        sync(workout(WorkoutType.RUNNING, 30, "08:00", "08:30"));

        HealthActivitySyncResponse response = sync(workout(WorkoutType.CYCLING, 30, "08:30", "09:00"));

        assertThat(response.gainedXp()).isEqualTo(105); // 90 + 15
        assertThat(activityRepository.count()).isEqualTo(2);
    }

    @Test
    void sameWorkoutResentIsDuplicateNotOverlap() {
        sync(workout(WorkoutType.RUNNING, 30, "08:00", "08:30"));

        HealthActivitySyncResponse again = sync(workout(WorkoutType.RUNNING, 30, "08:00", "08:30"));

        assertThat(again.gainedXp()).isZero();
        assertThat(again.activityResults().get(0).duplicate()).isTrue();
    }

    @Test
    void appliesOnlyFirstOfOverlappingSleepsInsideOneRequest() {
        HealthActivitySyncResponse response = sync(
                sleep(420, "2026-06-16T22:00:00Z", "2026-06-17T05:00:00Z"),
                sleep(300, "2026-06-17T01:00:00Z", "2026-06-17T06:00:00Z"));

        assertThat(response.gainedXp()).isEqualTo(80); // 첫 수면 60 + 품질 20
        assertThat(response.activityResults().get(1).duplicate()).isTrue();
        assertThat(response.activityResults().get(1).message()).isEqualTo("SLEEP 겹치는 기록이 이미 반영됨");
        assertThat(response.character().stats().recovery()).isEqualTo(1);
        assertThat(activityRepository.count()).isEqualTo(1);
        assertThat(activityRepository.findAll().get(0).getSleepMinutes()).isEqualTo(420);
    }

    @Test
    void skipsSleepWithSameStartButLaterEndOnResync() {
        sync(sleep(420, "2026-06-16T22:00:00Z", "2026-06-17T05:00:00Z"));

        HealthActivitySyncResponse resync = sync(
                steps(3_000),
                sleep(480, "2026-06-16T22:00:00Z", "2026-06-17T06:00:00Z"));

        assertThat(resync.activityResults()).extracting(r -> r.duplicate()).containsExactly(false, true);
        assertThat(resync.gainedXp()).isEqualTo(15);
    }

    @Test
    void formatViolationStillRejectsWholeRequest() {
        assertThatThrownBy(() -> sync(
                steps(8_500),
                sleep(500, "2026-06-16T22:00:00Z", "2026-06-17T05:00:00Z")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("수면 시간(sleepMinutes)이 시작~종료 구간 길이보다 깁니다.");
        assertThat(activityRepository.count()).isZero();
    }

    @Test
    void mixedRequestKeepsResultOrderWhenLargestStepsComesLast() {
        HealthActivitySyncResponse response = sync(
                steps(3_000),
                workout(WorkoutType.RUNNING, 30, "08:00", "08:30"),
                sleep(450, "2026-06-16T22:00:00Z", "2026-06-17T05:30:00Z"),
                steps(9_000));

        assertThat(response.activityResults()).extracting(r -> r.type())
                .containsExactly(ActivityType.STEPS, ActivityType.WORKOUT, ActivityType.SLEEP, ActivityType.STEPS);
        assertThat(response.activityResults()).extracting(r -> r.duplicate())
                .containsExactly(true, false, false, false);
        assertThat(response.activityResults()).extracting(r -> r.gainedXp())
                .containsExactly(0, 105, 80, 75);
        assertThat(response.gainedXp()).isEqualTo(260);
        assertThat(activityRepository.count()).isEqualTo(3);
    }

    @Test
    void workoutMayOverlapSleep() {
        HealthActivitySyncResponse response = sync(
                sleep(420, "2026-06-16T22:00:00Z", "2026-06-17T05:00:00Z"),
                workout(WorkoutType.WALKING, 30, "04:30", "05:00"));

        assertThat(response.activityResults()).noneMatch(HealthActivitySyncResponse.ActivityResultResponse::duplicate);
        assertThat(activityRepository.count()).isEqualTo(2);
    }

    // ---- 일일 XP 상한 ----

    @Test
    void clipsXpThatWouldExceedDailyCap() {
        // 200 + 200 + 100(걸음 상한) + 80(수면) = 580
        sync(
                workout(WorkoutType.RUNNING, 70, "06:00", "07:10"),
                workout(WorkoutType.SWIMMING, 70, "08:00", "09:10"),
                steps(16_000),
                sleep(450, "2026-06-16T22:00:00Z", "2026-06-17T05:30:00Z"));

        HealthActivitySyncResponse response = sync(workout(WorkoutType.CYCLING, 70, "10:00", "11:10"));

        assertThat(response.gainedXp()).isEqualTo(ActivityXpCalculator.DAILY_XP_CAP - 580);
        assertThat(response.activityResults().get(0).message()).isEqualTo("자전거 완료 +20 XP (일일 XP 상한 도달)");
        assertThat(response.character().totalXp()).isEqualTo(ActivityXpCalculator.DAILY_XP_CAP);
        assertThat(activityRepository.sumGainedXpByUserIdAndActivityDate(USER, DAY))
                .isEqualTo(ActivityXpCalculator.DAILY_XP_CAP);

        HealthActivitySyncResponse capped = sync(workout(WorkoutType.RUNNING, 30, "12:00", "12:30"));
        assertThat(capped.gainedXp()).isZero();
        assertThat(capped.activityResults().get(0).duplicate()).isFalse();
        assertThat(capped.character().stats().vit()).isEqualTo(8); // 스탯은 상한과 무관하게 반영
    }

    @Test
    void stepsDifferenceIsClippedByDailyCapAndGoalsStillUpdate() {
        // 200 + 200 + 80 + 105 = 585 → 남은 상한 15
        sync(
                workout(WorkoutType.RUNNING, 70, "06:00", "07:10"),
                workout(WorkoutType.SWIMMING, 70, "08:00", "09:10"),
                sleep(450, "2026-06-16T22:00:00Z", "2026-06-17T05:30:00Z"),
                workout(WorkoutType.RUNNING, 30, "10:00", "10:30"));

        HealthActivitySyncResponse morning = sync(steps(9_000));
        assertThat(morning.gainedXp()).isEqualTo(15);                // 75 중 15만 지급
        assertThat(morning.character().stats().discipline()).isEqualTo(2);
        HealthActivity log = activityRepository.findByExternalKey(USER + "|" + DAY + "|STEPS").orElseThrow();
        assertThat(log.getGainedXp()).isEqualTo(15);                 // 실제 지급액 기록

        HealthActivitySyncResponse evening = sync(steps(12_000));
        assertThat(evening.gainedXp()).isZero();
        assertThat(evening.activityResults().get(0).duplicate()).isFalse();
        assertThat(evening.activityResults().get(0).message()).isEqualTo("걸음 수 보상 +0 XP (일일 XP 상한 도달)");
        assertThat(evening.character().totalXp()).isEqualTo(ActivityXpCalculator.DAILY_XP_CAP);
        assertThat(evening.goals()).anySatisfy(goal -> {
            assertThat(goal.type()).isEqualTo(ActivityType.STEPS);
            assertThat(goal.current()).isEqualTo(12_000);
        });
    }

    @Test
    void dailyCapIsPerDate() {
        sync(
                workout(WorkoutType.RUNNING, 70, "06:00", "07:10"),
                workout(WorkoutType.SWIMMING, 70, "08:00", "09:10"),
                workout(WorkoutType.CYCLING, 70, "10:00", "11:10"));

        HealthActivitySyncResponse nextDay = syncService.sync(new HealthActivitySyncRequest(
                USER, DAY.plusDays(1), List.of(steps(8_500))));

        assertThat(nextDay.gainedXp()).isEqualTo(70);
    }

    // ---- 오버플로 포화 ----

    @Test
    void workoutGoalMinutesSaturateInsteadOfOverflowing() {
        for (int i = 0; i < 2; i++) {
            activityRepository.save(new HealthActivity(USER, DAY, ActivityType.WORKOUT, "OTHER",
                    Integer.MAX_VALUE, BigDecimal.ZERO, BigDecimal.ZERO, null, null, null, 0,
                    "legacy-" + i, null, null));
        }

        HealthActivitySyncResponse response = sync(steps(1_000));

        assertThat(response.goals()).anySatisfy(goal -> {
            assertThat(goal.type()).isEqualTo(ActivityType.WORKOUT);
            assertThat(goal.current()).isEqualTo(Integer.MAX_VALUE);
            assertThat(goal.achieved()).isTrue();
        });
    }

    // ---- helpers ----

    private HealthActivitySyncResponse sync(ActivityPayload... activities) {
        return syncService.sync(new HealthActivitySyncRequest(USER, DAY, List.of(activities)));
    }

    private static ActivityPayload steps(int value) {
        return new ActivityPayload(ActivityType.STEPS, null, null, null, null, value, null, null, null, null);
    }

    private static ActivityPayload workout(WorkoutType type, int minutes, String start, String end) {
        return new ActivityPayload(ActivityType.WORKOUT, type, minutes, null, null, null, null, null,
                Instant.parse("2026-06-17T" + start + ":00Z"), Instant.parse("2026-06-17T" + end + ":00Z"));
    }

    private static ActivityPayload sleep(int minutes, String start, String end) {
        return new ActivityPayload(ActivityType.SLEEP, null, null, null, null, null, minutes, 90,
                Instant.parse(start), Instant.parse(end));
    }
}
