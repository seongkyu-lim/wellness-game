package com.wellnessgame.activity;

import com.wellnessgame.api.HealthActivitySyncRequest.ActivityPayload;
import org.springframework.stereotype.Component;

@Component
public class ActivityXpCalculator {
    public int calculate(ActivityPayload activity) {
        return switch (activity.type()) {
            case STEPS -> stepsXp(activity.steps());
            case WORKOUT -> workoutXp(activity);
            case SLEEP -> sleepXp(activity.sleepMinutes(), activity.sleepScore());
        };
    }

    public static final int STEPS_GOAL = 8_000;
    public static final int WORKOUT_GOAL_MINUTES = 30;
    public static final int SLEEP_GOAL_MINUTES = 420;

    /**
     * 사용자·날짜별 지급 XP 합계 상한. 활동별 상한 기준 하루 정상 최대치
     * (걸음 100 + 수면 80 + 최대치 운동 2회 400 = 580)를 넉넉히 반올림한 값.
     */
    public static final int DAILY_XP_CAP = 600;

    private static final int STEPS_XP_CAP = 100;
    private static final int WORKOUT_XP_CAP = 200;
    public static final int SLEEP_QUALITY_BONUS_SCORE = 80;

    public int stepsXp(Integer steps) {
        int value = Math.max(value(steps), 0);
        int xp = (value / 1_000) * 5;
        if (value >= STEPS_GOAL) {
            xp += 30;
        }
        return Math.min(xp, STEPS_XP_CAP);
    }

    public int workoutXp(ActivityPayload activity) {
        double xp = value(activity.durationMinutes()) * 3.0
                + Math.floor(decimal(activity.calories()) / 10.0)
                + workoutTypeBonus(activity.workoutType());
        return clamp((int) Math.round(xp), 0, WORKOUT_XP_CAP);
    }

    private int workoutTypeBonus(WorkoutType type) {
        return switch (type == null ? WorkoutType.OTHER : type) {
            case SWIMMING -> 20;
            case RUNNING -> 15;
            case CYCLING -> 15;
            case WALKING -> 5;
            case STRENGTH_TRAINING -> 15;
            case OTHER -> 0;
        };
    }

    public int sleepXp(Integer sleepMinutes, Integer providedScore) {
        int minutes = value(sleepMinutes);
        int xp = sleepBaseXp(minutes);
        if (resolvedSleepScore(minutes, providedScore) >= SLEEP_QUALITY_BONUS_SCORE) {
            xp += 20;
        }
        return xp;
    }

    private int sleepBaseXp(int minutes) {
        if (minutes >= 420 && minutes <= 540) {
            return 60;
        }
        if (minutes > 540) {
            return 40;
        }
        if (minutes >= 360) {
            return 40;
        }
        if (minutes >= 300) {
            return 25;
        }
        return 10;
    }

    public int resolvedSleepScore(int minutes, Integer providedScore) {
        return providedScore == null
                ? sleepScore(minutes)
                : clamp(providedScore, 0, 100);
    }

    public int sleepScore(int minutes) {
        if (minutes >= 420 && minutes <= 540) {
            return 90;
        }
        if (minutes >= 360) {
            return 70;
        }
        if (minutes >= 300) {
            return 50;
        }
        return 30;
    }

    private static int value(Integer value) {
        return value == null ? 0 : value;
    }

    private static int clamp(int value, int min, int max) {
        return Math.min(Math.max(value, min), max);
    }

    private static double decimal(java.math.BigDecimal value) {
        return value == null ? 0 : value.doubleValue();
    }
}
