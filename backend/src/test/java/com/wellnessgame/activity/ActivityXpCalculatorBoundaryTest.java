package com.wellnessgame.activity;

import com.wellnessgame.api.HealthActivitySyncRequest.ActivityPayload;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/** XP 계산 경계값: 걸음 구간·목표, 운동 상한·최소 시간·종목 보너스, 수면 구간·품질 보너스. */
class ActivityXpCalculatorBoundaryTest {
    private final ActivityXpCalculator calculator = new ActivityXpCalculator();

    @ParameterizedTest(name = "걸음 {0} -> {1} XP")
    @CsvSource({
            "0,0",
            "999,0",
            "1000,5",
            "7999,35",     // 목표 직전: 7*5
            "8000,70",     // 목표 도달: 8*5 + 30
            "17999,100",   // 17*5 + 30 = 115 -> 상한 100
            "100000,100"
    })
    void stepsXpAtBoundaries(int steps, int expected) {
        assertThat(calculator.stepsXp(steps)).isEqualTo(expected);
    }

    @Test
    void stepsXpTreatsNullAndNegativeAsZero() {
        assertThat(calculator.stepsXp(null)).isZero();
        assertThat(calculator.stepsXp(-500)).isZero();
    }

    @Test
    void stepsXpStaysWithinDailyStepsCapForAnyRealisticInput() {
        for (int steps = 0; steps <= 100_000; steps += 250) {
            assertThat(calculator.stepsXp(steps)).isBetween(0, 100);
        }
    }

    private static ActivityPayload workout(WorkoutType type, Integer minutes, String calories) {
        return new ActivityPayload(ActivityType.WORKOUT, type, minutes,
                calories == null ? null : new BigDecimal(calories), null, null, null, null, null, null);
    }

    @ParameterizedTest(name = "{0} 종목 보너스 {1}")
    @CsvSource({
            "SWIMMING,20", "RUNNING,15", "CYCLING,15", "WALKING,5", "STRENGTH_TRAINING,15", "OTHER,0"
    })
    void workoutTypeBonusIsAddedToTimeXp(WorkoutType type, int bonus) {
        assertThat(calculator.workoutXp(workout(type, 10, null))).isEqualTo(30 + bonus);
    }

    @Test
    void workoutWithoutTypeGetsNoBonus() {
        assertThat(calculator.workoutXp(workout(null, 10, null))).isEqualTo(30);
    }

    @Test
    void workoutMinimumMinutesBoundary() {
        assertThat(calculator.workoutXp(workout(WorkoutType.RUNNING, 4, "500"))).isZero();
        assertThat(calculator.workoutXp(workout(WorkoutType.RUNNING, null, "500"))).isZero();
        assertThat(calculator.workoutXp(workout(WorkoutType.RUNNING, 5, null))).isEqualTo(30); // 15 + 15
    }

    @Test
    void workoutCaloriesAreFlooredPerTenAndContributeToXp() {
        assertThat(calculator.workoutXp(workout(WorkoutType.OTHER, 10, "9.99"))).isEqualTo(30);
        assertThat(calculator.workoutXp(workout(WorkoutType.OTHER, 10, "10"))).isEqualTo(31);
        assertThat(calculator.workoutXp(workout(WorkoutType.OTHER, 10, "199"))).isEqualTo(49);
    }

    @Test
    void workoutXpIsCappedAtTwoHundred() {
        // 60*3 + 20 = 200 : 상한 경계
        assertThat(calculator.workoutXp(workout(WorkoutType.SWIMMING, 60, null))).isEqualTo(200);
        assertThat(calculator.workoutXp(workout(WorkoutType.SWIMMING, 61, null))).isEqualTo(200);
        assertThat(calculator.workoutXp(workout(WorkoutType.SWIMMING, 720, "10000"))).isEqualTo(200);
    }

    @ParameterizedTest(name = "수면 {0}분 점수없음 -> {1} XP")
    @CsvSource({
            "0,10", "299,10",
            "300,25", "359,25",
            "360,40", "419,40",   // 점수 70 이라 보너스 없음
            "420,80", "540,80",   // 정액 60 + 점수 90 이라 보너스 20
            "541,40", "960,40"
    })
    void sleepXpByDurationBoundaries(int minutes, int expected) {
        assertThat(calculator.sleepXp(minutes, null)).isEqualTo(expected);
    }

    @Test
    void sleepScoreBonusStartsExactlyAtEighty() {
        assertThat(calculator.sleepXp(480, 79)).isEqualTo(60);
        assertThat(calculator.sleepXp(480, 80)).isEqualTo(80);
        assertThat(calculator.sleepXp(100, 80)).isEqualTo(30); // 짧은 수면도 점수가 높으면 보너스
    }

    @Test
    void providedSleepScoreIsClampedAndNullFallsBackToDuration() {
        assertThat(calculator.resolvedSleepScore(480, 150)).isEqualTo(100);
        assertThat(calculator.resolvedSleepScore(480, -3)).isZero();
        assertThat(calculator.resolvedSleepScore(480, null)).isEqualTo(90);
        assertThat(calculator.resolvedSleepScore(100, null)).isEqualTo(30);
        assertThat(calculator.sleepXp(null, null)).isEqualTo(10);
    }

    @Test
    void sleepScoreBoundariesByDuration() {
        assertThat(calculator.sleepScore(540)).isEqualTo(90);
        assertThat(calculator.sleepScore(541)).isEqualTo(70);
        assertThat(calculator.sleepScore(419)).isEqualTo(70);
        assertThat(calculator.sleepScore(360)).isEqualTo(70);
        assertThat(calculator.sleepScore(359)).isEqualTo(50);
        assertThat(calculator.sleepScore(300)).isEqualTo(50);
        assertThat(calculator.sleepScore(299)).isEqualTo(30);
    }

    @Test
    void calculateDispatchesByActivityType() {
        ActivityPayload steps = new ActivityPayload(ActivityType.STEPS, null, null, null, null, 8_000, null, null, null, null);
        ActivityPayload sleep = new ActivityPayload(ActivityType.SLEEP, null, null, null, null, null, 450, 90, null, null);

        assertThat(calculator.calculate(steps)).isEqualTo(70);
        assertThat(calculator.calculate(workout(WorkoutType.RUNNING, 30, null))).isEqualTo(105);
        assertThat(calculator.calculate(sleep)).isEqualTo(80);
    }

    @Test
    void dailyCapsAreConsistentWithPerActivityCaps() {
        // 걸음 100 + 수면 80 + 운동 2회 400 = 580 <= 일일 상한 600
        assertThat(100 + 80 + ActivityXpCalculator.DAILY_WORKOUT_XP_CAP)
                .isLessThanOrEqualTo(ActivityXpCalculator.DAILY_XP_CAP);
        assertThat(ActivityXpCalculator.DAILY_WORKOUT_XP_CAP).isEqualTo(2 * 200);
    }
}
