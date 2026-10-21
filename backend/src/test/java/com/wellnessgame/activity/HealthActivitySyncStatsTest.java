package com.wellnessgame.activity;

import com.wellnessgame.api.HealthActivitySyncRequest;
import com.wellnessgame.api.HealthActivitySyncRequest.ActivityPayload;
import com.wellnessgame.api.HealthActivitySyncResponse;
import com.wellnessgame.api.HealthActivitySyncResponse.StatsResponse;
import com.wellnessgame.character.UserCharacterRepository;
import com.wellnessgame.support.ActivityPayloads;
import com.wellnessgame.support.MutableClock;
import com.wellnessgame.support.TestClockConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static com.wellnessgame.support.ActivityPayloads.sleep;
import static com.wellnessgame.support.ActivityPayloads.steps;
import static org.assertj.core.api.Assertions.assertThat;

/** 종목별 스탯 보상, 수면 품질 기준 경계, 응답 문구 같은 동기화 세부 규칙. */
@SpringBootTest
@Import(TestClockConfig.class)
class HealthActivitySyncStatsTest {
    private static final String USER = "stats-user";
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

    @ParameterizedTest(name = "{0} -> str {1}, vit {2}")
    @CsvSource({
            "STRENGTH_TRAINING,2,1",
            "SWIMMING,1,2",
            "RUNNING,1,2",
            "CYCLING,1,2",
            "WALKING,1,2",
            "OTHER,1,1"
    })
    void workoutTypeDeterminesStatRewards(WorkoutType type, int str, int vit) {
        HealthActivitySyncResponse response = sync(workout(type));

        StatsResponse stats = response.character().stats();
        assertThat(stats.str()).isEqualTo(str);
        assertThat(stats.vit()).isEqualTo(vit);
        assertThat(stats.intStat()).isZero();
    }

    @Test
    void workoutWithoutTypeIsTreatedAsOther() {
        HealthActivitySyncResponse response = sync(workout(null));

        assertThat(response.activityResults().get(0).source()).isEqualTo("OTHER");
        assertThat(response.character().stats().str()).isEqualTo(1);
        assertThat(response.character().stats().vit()).isEqualTo(1);
        assertThat(response.gainedXp()).isEqualTo(90); // 30분*3 + 보너스 0
    }

    @Test
    void sleepBelowQualityScoreGrantsRecoveryAndSingleIntelligence() {
        HealthActivitySyncResponse response = sync(sleep(450, 79, "2026-06-16T22:00:00Z", "2026-06-17T05:30:00Z"));

        assertThat(response.gainedXp()).isEqualTo(60); // 품질 보너스 없음
        assertThat(response.character().stats().recovery()).isEqualTo(1);
        assertThat(response.character().stats().intStat()).isEqualTo(1);
    }

    @Test
    void sleepAtQualityScoreBoundaryGrantsExtraIntelligence() {
        HealthActivitySyncResponse response = sync(sleep(450, 80, "2026-06-16T22:00:00Z", "2026-06-17T05:30:00Z"));

        assertThat(response.gainedXp()).isEqualTo(80);
        assertThat(response.character().stats().intStat()).isEqualTo(2);
    }

    @Test
    void stepsBelowGoalGrantOnlyBaseDiscipline() {
        HealthActivitySyncResponse response = sync(steps(7_999));

        assertThat(response.character().stats().discipline()).isEqualTo(1);
        assertThat(response.gainedXp()).isEqualTo(35);
    }

    @Test
    void stepsBelowOneThousandGrantNoXpButStillRecordLog() {
        HealthActivitySyncResponse response = sync(steps(999));

        assertThat(response.gainedXp()).isZero();
        assertThat(response.activityResults().get(0).duplicate()).isFalse();
        assertThat(activityRepository.count()).isEqualTo(1);
        // XP 가 지급되지 않으면 스탯도 없다.
        assertThat(response.character().stats().discipline()).isZero();
    }

    @Test
    void stepsLaterReachingFirstThousandGrantBaseDisciplineThenGoalBonus() {
        sync(steps(999));

        HealthActivitySyncResponse reached = sync(steps(1_000));
        assertThat(reached.gainedXp()).isEqualTo(5);
        assertThat(reached.character().stats().discipline()).isEqualTo(1); // 기본 보상은 이제야 지급

        HealthActivitySyncResponse goal = sync(steps(8_000));
        assertThat(goal.gainedXp()).isEqualTo(65);
        assertThat(goal.character().stats().discipline()).isEqualTo(2);    // 목표 달성 +1
    }

    @Test
    void levelUpFlagIsReportedExactlyAtThresholdAndNotBefore() {
        // 걸음 8000 = 70 XP (레벨 1 -> 100 필요)
        assertThat(sync(steps(8_000)).levelUp()).isFalse();
        // 30 XP 더: 100 에 정확히 도달
        HealthActivitySyncResponse up = sync(steps(8_000), workout(WorkoutType.OTHER, 10, "08:00", "08:10"));
        assertThat(up.levelUp()).isTrue();
        assertThat(up.character().level()).isEqualTo(2);
        assertThat(up.character().currentXp()).isZero();
        assertThat(up.character().nextLevelXp()).isEqualTo(150);
    }

    // ---- helpers ----

    private HealthActivitySyncResponse sync(ActivityPayload... activities) {
        return syncService.sync(new HealthActivitySyncRequest(USER, DAY, List.of(activities)));
    }

    private static ActivityPayload workout(WorkoutType type) {
        return workout(type, 30, "08:00", "08:30");
    }

    private static ActivityPayload workout(WorkoutType type, int minutes, String start, String end) {
        return ActivityPayloads.workout(type, minutes, "2026-06-17T" + start + ":00Z", "2026-06-17T" + end + ":00Z");
    }
}
