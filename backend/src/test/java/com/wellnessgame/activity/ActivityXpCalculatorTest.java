package com.wellnessgame.activity;

import com.wellnessgame.api.HealthActivitySyncRequest.ActivityPayload;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class ActivityXpCalculatorTest {
    private final ActivityXpCalculator calculator = new ActivityXpCalculator();

    @Test
    void capsStepsXpAtEighty() {
        assertThat(calculator.stepsXp(8_500)).isEqualTo(42);
        assertThat(calculator.stepsXp(50_000)).isEqualTo(80);
    }

    @Test
    void calculatesWorkoutXpWithActivityBonus() {
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

        assertThat(calculator.workoutXp(swimming)).isEqualTo(164);
    }

    @Test
    void calculatesSleepScoreFromDuration() {
        assertThat(calculator.sleepScore(450)).isEqualTo(90);
        assertThat(calculator.sleepScore(390)).isEqualTo(70);
        assertThat(calculator.sleepScore(330)).isEqualTo(50);
        assertThat(calculator.sleepScore(240)).isEqualTo(30);
    }

    @Test
    void appliesRecoveryBonusAndShortSleepPenalty() {
        assertThat(calculator.sleepXp(450, 90)).isEqualTo(120);
        assertThat(calculator.sleepXp(240, 30)).isEqualTo(15);
    }
}

