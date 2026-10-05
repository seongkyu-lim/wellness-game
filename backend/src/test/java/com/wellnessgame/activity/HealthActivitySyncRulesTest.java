package com.wellnessgame.activity;

import com.wellnessgame.api.HealthActivitySyncRequest;
import com.wellnessgame.api.HealthActivitySyncRequest.ActivityPayload;
import com.wellnessgame.api.HealthActivitySyncResponse;
import com.wellnessgame.character.UserCharacter;
import com.wellnessgame.character.UserCharacterRepository;
import com.wellnessgame.support.ActivityPayloads;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.wellnessgame.support.ActivityPayloads.sleep;
import static com.wellnessgame.support.ActivityPayloads.steps;
import static com.wellnessgame.support.ActivityPayloads.workout;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * #42 동기화 규칙: 운동 겹침 건너뛰기, SLEEP 날짜별 upsert, 값 범위 위반 건너뛰기, 동시성, 일일 XP 상한, goals 포화.
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
        sync(workoutAt(WorkoutType.RUNNING, 30, "08:00", "08:30"));

        HealthActivitySyncResponse response = sync(
                steps(8_500),
                workoutAt(WorkoutType.CYCLING, 31, "08:29", "09:00"),
                workoutAt(WorkoutType.WALKING, 30, "10:00", "10:30"));

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
        sync(workoutAt(WorkoutType.RUNNING, 30, "08:00", "08:30"));

        HealthActivitySyncResponse response = sync(workoutAt(WorkoutType.CYCLING, 30, "08:30", "09:00"));

        assertThat(response.gainedXp()).isEqualTo(105); // 90 + 15
        assertThat(activityRepository.count()).isEqualTo(2);
    }

    @Test
    void sameWorkoutResentIsDuplicateNotOverlap() {
        sync(workoutAt(WorkoutType.RUNNING, 30, "08:00", "08:30"));

        HealthActivitySyncResponse again = sync(workoutAt(WorkoutType.RUNNING, 30, "08:00", "08:30"));

        assertThat(again.gainedXp()).isZero();
        assertThat(again.activityResults().get(0).duplicate()).isTrue();
    }

    // ---- SLEEP: 날짜별 1건 upsert ----

    @Test
    void sleepIsNotOverlapCheckedAndOnlyLargestInRequestIsApplied() {
        HealthActivitySyncResponse response = sync(
                sleepWithScore90(300, "2026-06-17T01:00:00Z", "2026-06-17T06:00:00Z"),
                sleepWithScore90(420, "2026-06-16T22:00:00Z", "2026-06-17T05:00:00Z"),
                sleepWithScore90(400, "2026-06-16T22:30:00Z", "2026-06-17T05:10:00Z"));

        assertThat(response.activityResults()).extracting(r -> r.duplicate()).containsExactly(true, false, true);
        assertThat(response.activityResults().get(0).message()).isEqualTo("SLEEP 이미 반영됨");
        assertThat(response.gainedXp()).isEqualTo(80); // 420분 60 + 품질 20
        assertThat(activityRepository.count()).isEqualTo(1);
        assertThat(activityRepository.findAll().get(0).getSleepMinutes()).isEqualTo(420);
    }

    @Test
    void longerSleepResyncGrantsOnlyDifferenceAndQualityIntOnce() {
        HealthActivitySyncResponse first = sync(sleep(300, 50, "2026-06-16T23:00:00Z", "2026-06-17T04:00:00Z"));
        assertThat(first.gainedXp()).isEqualTo(25);
        assertThat(first.character().stats().recovery()).isEqualTo(1);
        assertThat(first.character().stats().intStat()).isEqualTo(1);

        HealthActivitySyncResponse longer = sync(sleep(450, 90, "2026-06-16T23:00:00Z", "2026-06-17T06:30:00Z"));
        assertThat(longer.gainedXp()).isEqualTo(55);                         // 80 - 25
        assertThat(longer.activityResults().get(0).duplicate()).isFalse();
        assertThat(longer.character().stats().recovery()).isEqualTo(1);      // 첫 생성 때만
        assertThat(longer.character().stats().intStat()).isEqualTo(2);       // 품질 80 미만 → 이상 전환 +1

        HealthActivitySyncResponse evenLonger = sync(sleep(480, 95, "2026-06-16T23:00:00Z", "2026-06-17T07:00:00Z"));
        assertThat(evenLonger.gainedXp()).isZero();                          // 80 → 80
        assertThat(evenLonger.activityResults().get(0).duplicate()).isFalse();
        assertThat(evenLonger.character().stats().intStat()).isEqualTo(2);   // 재지급 없음

        HealthActivity log = activityRepository.findByExternalKey(USER + "|" + DAY + "|SLEEP").orElseThrow();
        assertThat(log.getSleepMinutes()).isEqualTo(480);
        assertThat(log.getGainedXp()).isEqualTo(80);
        assertThat(log.getEndedAt()).isEqualTo(Instant.parse("2026-06-17T07:00:00Z"));
        assertThat(activityRepository.count()).isEqualTo(1);
    }

    @Test
    void sameOrShorterSleepResyncIsDuplicate() {
        sync(sleep(450, 90, "2026-06-16T22:00:00Z", "2026-06-17T05:30:00Z"));

        HealthActivitySyncResponse same = sync(sleep(450, 90, "2026-06-16T22:00:00Z", "2026-06-17T05:30:00Z"));
        HealthActivitySyncResponse shorter = sync(sleep(400, 90, "2026-06-16T22:30:00Z", "2026-06-17T05:30:00Z"));

        assertThat(same.activityResults().get(0).duplicate()).isTrue();
        assertThat(shorter.activityResults().get(0).duplicate()).isTrue();
        assertThat(shorter.character().totalXp()).isEqualTo(80);
    }

    @Test
    void sameDayNapExtendsSleepAndNextDaySleepIncludingNapIsSeparateRecord() {
        // 1일차 아침: 밤잠만 (22:00~06:00, 480분)
        HealthActivitySyncResponse morning = sync(steps(3_000),
                sleep(480, 90, "2026-06-16T22:00:00Z", "2026-06-17T06:00:00Z"));
        assertThat(morning.activityResults().get(1).gainedXp()).isEqualTo(80);

        // 1일차 오후: iOS 조회 범위에 낮잠(14:00~15:00)이 합쳐져 같은 시작·더 늦은 종료로 다시 옴
        HealthActivitySyncResponse afternoon = sync(steps(9_000),
                sleep(540, 90, "2026-06-16T22:00:00Z", "2026-06-17T15:00:00Z"));
        assertThat(afternoon.activityResults()).extracting(r -> r.duplicate()).containsExactly(false, false);
        assertThat(afternoon.activityResults().get(1).gainedXp()).isZero(); // 540분도 60+20 → 차액 0

        // 2일차: 조회 범위(전날 12:00~)에 1일차 낮잠 + 밤잠이 합쳐져 옴 → 2일차 기록으로 별도 반영
        clock.setInstant(Instant.parse("2026-06-18T23:00:00Z"));
        HealthActivitySyncResponse nextDay = syncService.sync(new HealthActivitySyncRequest(USER, DAY.plusDays(1),
                List.of(sleep(540, 90, "2026-06-17T14:00:00Z", "2026-06-18T07:00:00Z"))));
        assertThat(nextDay.activityResults().get(0).duplicate()).isFalse();
        assertThat(nextDay.gainedXp()).isEqualTo(80);
        assertThat(nextDay.goals()).anySatisfy(goal -> {
            assertThat(goal.type()).isEqualTo(ActivityType.SLEEP);
            assertThat(goal.current()).isEqualTo(540);
        });
        assertThat(activityRepository.count()).isEqualTo(3); // STEPS 1 + SLEEP(1일차) 1 + SLEEP(2일차) 1
    }

    // ---- 값 범위 위반: 해당 항목만 건너뛰기 ----

    @Test
    void valueViolationsSkipOnlyTheOffendingItems() {
        HealthActivitySyncResponse response = sync(
                steps(8_500),
                steps(200_000),
                workout(WorkoutType.RUNNING, 30, new BigDecimal("700"), null,
                        Instant.parse("2026-06-17T06:00:00Z"), Instant.parse("2026-06-17T06:30:00Z")),
                sleep(500, 90, "2026-06-16T22:00:00Z", "2026-06-17T05:00:00Z"),
                workout(WorkoutType.SWIMMING, 30, "2026-06-15T23:00:00Z", "2026-06-15T23:30:00Z"),
                workout(WorkoutType.WALKING, 0, "2026-06-17T09:00:00Z", "2026-06-17T09:00:00Z"),
                workout(WorkoutType.CYCLING, 30, "2026-06-17T23:10:00Z", "2026-06-17T23:40:00Z"),
                workoutAt(WorkoutType.RUNNING, 30, "08:00", "08:30"));

        assertThat(response.activityResults()).extracting(r -> r.duplicate())
                .containsExactly(false, true, true, true, true, true, true, false);
        assertThat(response.activityResults()).extracting(r -> r.message()).containsExactly(
                "걸음 수 보상 +70 XP",
                "STEPS 기록을 반영하지 않았어요: 걸음 수는 0 이상 100000 이하여야 합니다.",
                "RUNNING 기록을 반영하지 않았어요: 칼로리가 운동 시간 대비 너무 큽니다(분당 최대 20kcal).",
                "SLEEP 기록을 반영하지 않았어요: 수면 시간(sleepMinutes)이 시작~종료 구간 길이보다 깁니다.",
                "SWIMMING 기록을 반영하지 않았어요: 활동 시간이 동기화 날짜 범위를 벗어났습니다.",
                "WALKING 기록을 반영하지 않았어요: 운동 종료 시간은 시작 시간보다 늦어야 합니다.",
                "CYCLING 기록을 반영하지 않았어요: 활동 시간은 현재 시각 이후일 수 없습니다.",
                "달리기 완료 +105 XP");
        assertThat(response.gainedXp()).isEqualTo(175);
        assertThat(activityRepository.count()).isEqualTo(2);
        assertThat(activityRepository.findByExternalKey(USER + "|" + DAY + "|STEPS").orElseThrow().getSteps())
                .isEqualTo(8_500);
    }

    @Test
    void structuralErrorStillRejectsWholeRequest() {
        assertThatThrownBy(() -> sync(
                steps(8_500),
                sleep(420, 90, "2026-06-17T05:00:00Z", "2026-06-16T22:00:00Z")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("활동 종료 시간은 시작 시간 이후여야 합니다.");
        assertThat(activityRepository.count()).isZero();
    }

    @Test
    void nullWorkoutDurationIsFilledFromSpan() {
        HealthActivitySyncResponse response = sync(workoutAt(WorkoutType.RUNNING, null, "08:00", "08:40"));

        assertThat(response.gainedXp()).isEqualTo(135); // 40*3 + 15
        assertThat(activityRepository.findAll().get(0).getDurationMinutes()).isEqualTo(40);
        assertThat(response.goals()).anySatisfy(goal -> {
            assertThat(goal.type()).isEqualTo(ActivityType.WORKOUT);
            assertThat(goal.current()).isEqualTo(40);
        });
    }

    @Test
    void mixedRequestKeepsResultOrderWhenLargestStepsComesLast() {
        HealthActivitySyncResponse response = sync(
                steps(3_000),
                workoutAt(WorkoutType.RUNNING, 30, "08:00", "08:30"),
                sleepWithScore90(450, "2026-06-16T22:00:00Z", "2026-06-17T05:30:00Z"),
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
                sleepWithScore90(420, "2026-06-16T22:00:00Z", "2026-06-17T05:00:00Z"),
                workoutAt(WorkoutType.WALKING, 30, "04:30", "05:00"));

        assertThat(response.activityResults()).noneMatch(HealthActivitySyncResponse.ActivityResultResponse::duplicate);
        assertThat(activityRepository.count()).isEqualTo(2);
    }

    // ---- 동시성 ----

    @Test
    void concurrentSyncsForSameUserDoNotExceedDailyCap() throws Exception {
        // #46 운동 상한(400) 이후 정상 동기화만으로는 600 에 닿지 않으므로 이전 지급분 120 을 미리 둔다.
        // 120 + 운동 200 + 걸음 100 + 수면 80 = 500 → 남은 총 상한 100 (운동 상한은 200 남음)
        seedPriorXp(120);
        sync(
                workoutAt(WorkoutType.RUNNING, 70, "06:00", "07:10"),
                steps(16_000),
                sleepWithScore90(450, "2026-06-16T22:00:00Z", "2026-06-17T05:30:00Z"));

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<HealthActivitySyncResponse> a = executor.submit(() -> {
                start.await();
                return sync(workoutAt(WorkoutType.CYCLING, 70, "10:00", "11:10"));
            });
            Future<HealthActivitySyncResponse> b = executor.submit(() -> {
                start.await();
                return sync(workoutAt(WorkoutType.STRENGTH_TRAINING, 70, "12:00", "13:10"));
            });
            start.countDown();
            int gained = a.get(10, TimeUnit.SECONDS).gainedXp() + b.get(10, TimeUnit.SECONDS).gainedXp();

            assertThat(gained).isEqualTo(100);
        } finally {
            executor.shutdownNow();
        }
        assertThat(activityRepository.sumGainedXpByUserIdAndActivityDate(USER, DAY))
                .isEqualTo(ActivityXpCalculator.DAILY_XP_CAP);
        assertThat(characterRepository.findByUserId(USER).orElseThrow().getTotalXp())
                .isEqualTo(ActivityXpCalculator.DAILY_XP_CAP);
    }

    // ---- 일일 XP 상한 ----

    @Test
    void clipsXpThatWouldExceedDailyCap() {
        // 이전 지급분 185 + 달리기 105 + 수영 110 + 100(걸음 상한) + 80(수면) = 580 (운동 상한은 185 남음)
        seedPriorXp(185);
        sync(
                workoutAt(WorkoutType.RUNNING, 30, "06:00", "06:30"),
                workoutAt(WorkoutType.SWIMMING, 30, "08:00", "08:30"),
                steps(16_000),
                sleepWithScore90(450, "2026-06-16T22:00:00Z", "2026-06-17T05:30:00Z"));

        HealthActivitySyncResponse response = sync(workoutAt(WorkoutType.CYCLING, 70, "10:00", "11:10"));

        assertThat(response.gainedXp()).isEqualTo(ActivityXpCalculator.DAILY_XP_CAP - 580);
        assertThat(response.activityResults().get(0).message()).isEqualTo("자전거 완료 +20 XP (일일 XP 상한 도달)");
        assertThat(response.character().totalXp()).isEqualTo(ActivityXpCalculator.DAILY_XP_CAP);
        assertThat(activityRepository.sumGainedXpByUserIdAndActivityDate(USER, DAY))
                .isEqualTo(ActivityXpCalculator.DAILY_XP_CAP);

        HealthActivitySyncResponse capped = sync(workoutAt(WorkoutType.RUNNING, 30, "12:00", "12:30"));
        assertThat(capped.gainedXp()).isZero();
        assertThat(capped.activityResults().get(0).duplicate()).isFalse();
        assertThat(capped.character().stats().vit()).isEqualTo(8); // 스탯은 상한과 무관하게 반영
    }

    @Test
    void stepsDifferenceIsClippedByDailyCapAndGoalsStillUpdate() {
        // 이전 지급분 105 + 운동 200 + 200 + 수면 80 = 585 → 남은 상한 15
        seedPriorXp(105);
        sync(
                workoutAt(WorkoutType.RUNNING, 70, "06:00", "07:10"),
                workoutAt(WorkoutType.SWIMMING, 70, "08:00", "09:10"),
                sleepWithScore90(450, "2026-06-16T22:00:00Z", "2026-06-17T05:30:00Z"));

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
                workoutAt(WorkoutType.RUNNING, 70, "06:00", "07:10"),
                workoutAt(WorkoutType.SWIMMING, 70, "08:00", "09:10"),
                workoutAt(WorkoutType.CYCLING, 70, "10:00", "11:10"));

        HealthActivitySyncResponse nextDay = syncService.sync(new HealthActivitySyncRequest(
                USER, DAY.plusDays(1), List.of(steps(8_500))));

        assertThat(nextDay.gainedXp()).isEqualTo(70);
    }

    @Test
    void workoutXpIsCappedPerDayEvenBelowTotalCap() {
        // 운동 200 + 105 = 305 → 운동 상한(400)까지 95 남음
        HealthActivitySyncResponse first = sync(
                workoutAt(WorkoutType.RUNNING, 70, "06:00", "07:10"),
                workoutAt(WorkoutType.RUNNING, 30, "08:00", "08:30"),
                workoutAt(WorkoutType.CYCLING, 70, "10:00", "11:10"));

        assertThat(first.activityResults()).extracting(r -> r.gainedXp()).containsExactly(200, 105, 95);
        assertThat(first.activityResults().get(2).message()).isEqualTo("자전거 완료 +95 XP (일일 XP 상한 도달)");
        assertThat(activityRepository.sumGainedXpByUserIdAndActivityDateAndType(USER, DAY, ActivityType.WORKOUT))
                .isEqualTo(ActivityXpCalculator.DAILY_WORKOUT_XP_CAP);

        // 다음 요청의 운동은 0 XP(스탯은 반영), 걸음·수면은 운동 상한과 무관하게 지급
        HealthActivitySyncResponse second = sync(
                workoutAt(WorkoutType.STRENGTH_TRAINING, 30, "12:00", "12:30"),
                steps(16_000),
                sleepWithScore90(450, "2026-06-16T22:00:00Z", "2026-06-17T05:30:00Z"));

        assertThat(second.activityResults()).extracting(r -> r.gainedXp()).containsExactly(0, 100, 80);
        assertThat(second.activityResults().get(0).duplicate()).isFalse();
        assertThat(second.activityResults().get(0).message()).isEqualTo("근력 운동 완료 +0 XP (일일 XP 상한 도달)");
        assertThat(second.character().stats().str()).isEqualTo(1 + 1 + 1 + 2); // 달리기·달리기·자전거 +1, 근력 +2
        assertThat(second.character().totalXp()).isEqualTo(580);
    }

    @Test
    void workoutCapIsPerDate() {
        sync(
                workoutAt(WorkoutType.RUNNING, 70, "06:00", "07:10"),
                workoutAt(WorkoutType.SWIMMING, 70, "08:00", "09:10"));

        clock.setInstant(Instant.parse("2026-06-18T23:00:00Z"));
        HealthActivitySyncResponse nextDay = syncService.sync(new HealthActivitySyncRequest(USER, DAY.plusDays(1),
                List.of(ActivityPayloads.workout(WorkoutType.RUNNING, 70,
                        "2026-06-18T06:00:00Z", "2026-06-18T07:10:00Z"))));

        assertThat(nextDay.gainedXp()).isEqualTo(200);
    }

    // ---- 신규 사용자 동시 동기화 (#49) ----

    @Test
    void concurrentFirstSyncsForNewUserBothSucceedWithSingleCharacter() throws Exception {
        String newUser = "brand-new-user";
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<HealthActivitySyncResponse> a = executor.submit(() -> {
                start.await();
                return syncService.sync(new HealthActivitySyncRequest(newUser, DAY,
                        List.of(workoutAt(WorkoutType.RUNNING, 30, "08:00", "08:30"))));
            });
            Future<HealthActivitySyncResponse> b = executor.submit(() -> {
                start.await();
                return syncService.sync(new HealthActivitySyncRequest(newUser, DAY,
                        List.of(workoutAt(WorkoutType.CYCLING, 30, "10:00", "10:30"))));
            });
            start.countDown();
            int gained = a.get(10, TimeUnit.SECONDS).gainedXp() + b.get(10, TimeUnit.SECONDS).gainedXp();

            assertThat(gained).isEqualTo(105 + 105);
        } finally {
            executor.shutdownNow();
        }
        assertThat(characterRepository.findAll()).filteredOn(c -> newUser.equals(c.getUserId())).hasSize(1);
        assertThat(characterRepository.findByUserId(newUser).orElseThrow().getTotalXp()).isEqualTo(210);
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

    /**
     * 그날 이미 지급된 XP(운동 외 유형)를 로그와 캐릭터에 함께 남긴다. 유형별 상한 도입 전 데이터처럼,
     * 정상 동기화만으로는 닿지 않는 일일 총 상한(600) 경계를 시험할 때 쓴다.
     */
    private void seedPriorXp(int xp) {
        activityRepository.save(new HealthActivity(USER, DAY, ActivityType.STEPS, "STEPS",
                null, BigDecimal.ZERO, BigDecimal.ZERO, null, null, null, xp,
                "seed-prior-xp|" + USER + "|" + DAY, null, null));
        UserCharacter character = new UserCharacter(USER);
        character.addXp(xp);
        characterRepository.save(character);
    }

    private HealthActivitySyncResponse sync(ActivityPayload... activities) {
        return syncService.sync(new HealthActivitySyncRequest(USER, DAY, List.of(activities)));
    }

    private static ActivityPayload workoutAt(WorkoutType type, Integer minutes, String start, String end) {
        return ActivityPayloads.workout(type, minutes, "2026-06-17T" + start + ":00Z", "2026-06-17T" + end + ":00Z");
    }

    private static ActivityPayload sleepWithScore90(int minutes, String start, String end) {
        return sleep(minutes, 90, start, end);
    }
}
