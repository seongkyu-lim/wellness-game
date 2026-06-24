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

    public int stepsXp(Integer steps) {
        return Math.min(Math.max(value(steps), 0) / 200, 80);
    }

    public int workoutXp(ActivityPayload activity) {
        double xp = value(activity.durationMinutes()) * 2.0
                + decimal(activity.calories()) * 0.1
                + decimal(activity.distanceMeters()) * 0.01;
        xp += switch (activity.workoutType() == null ? WorkoutType.OTHER : activity.workoutType()) {
            case SWIMMING -> 20;
            case RUNNING -> 10;
            case WALKING -> 5;
            default -> 0;
        };
        return Math.max(0, (int) Math.round(xp));
    }

    public int sleepXp(Integer sleepMinutes, Integer providedScore) {
        int minutes = value(sleepMinutes);
        int score = providedScore == null ? sleepScore(minutes) : Math.min(Math.max(providedScore, 0), 100);
        int xp = score;
        if (minutes >= 420 && minutes <= 540) {
            xp += 30;
        }
        if (minutes < 300) {
            xp = (int) Math.round(xp * 0.5);
        }
        return xp;
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

    private static double decimal(java.math.BigDecimal value) {
        return value == null ? 0 : value.doubleValue();
    }
}
