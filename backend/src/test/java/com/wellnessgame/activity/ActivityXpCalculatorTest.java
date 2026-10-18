package com.wellnessgame.activity;

import com.wellnessgame.api.HealthActivitySyncRequest.ActivityPayload;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class ActivityXpCalculatorTest {
    private final ActivityXpCalculator calculator = new ActivityXpCalculator();

    @Test
    void awardsStepsGoalBonusAndCapsAtHundred() {
        assertThat(calculator.stepsXp(8_500)).isEqualTo(70);   // 8*5 + 30 목표 보너스
        assertThat(calculator.stepsXp(2_000)).isEqualTo(10);   // 2*5, 목표 미달
        assertThat(calculator.stepsXp(50_000)).isEqualTo(100); // 상한
    }

    @Test
    void calculatesWorkoutXpWithTypeBonusAndCap() {
        ActivityPayload swimming = new ActivityPayload(
                ActivityType.WORKOUT,
                WorkoutType.SWIMMING,
                45,
                new BigDecimal("420"),
                new BigDecimal("1200"),
                null,
                null,
                null,
                null,
                null
        );

        assertThat(calculator.workoutXp(swimming)).isEqualTo(197); // 45*3 + 42 + 20
    }

    @Test
    void calculatesSleepScoreFromDuration() {
        assertThat(calculator.sleepScore(450)).isEqualTo(90);
        assertThat(calculator.sleepScore(390)).isEqualTo(70);
        assertThat(calculator.sleepScore(330)).isEqualTo(50);
        assertThat(calculator.sleepScore(240)).isEqualTo(30);
    }

    @Test
    void appliesFixedSleepXpWithQualityBonus() {
        assertThat(calculator.sleepXp(450, 90)).isEqualTo(80); // 60 정액 + 20 품질 보너스
        assertThat(calculator.sleepXp(240, 30)).isEqualTo(10); // 10 정액, 보너스 없음
    }
}

