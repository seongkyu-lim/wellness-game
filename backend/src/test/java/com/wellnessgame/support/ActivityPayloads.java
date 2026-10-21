package com.wellnessgame.support;

import com.wellnessgame.activity.ActivityType;
import com.wellnessgame.activity.WorkoutType;
import com.wellnessgame.api.HealthActivitySyncRequest.ActivityPayload;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 테스트용 동기화 payload 팩토리.
 */
public final class ActivityPayloads {
    private ActivityPayloads() {
    }

    public static ActivityPayload steps(int steps) {
        return new ActivityPayload(ActivityType.STEPS, null, null, null, null, steps, null, null, null, null);
    }

    public static ActivityPayload workout(WorkoutType type, Integer minutes, Instant start, Instant end) {
        return workout(type, minutes, null, null, start, end);
    }

    public static ActivityPayload workout(WorkoutType type, Integer minutes, String start, String end) {
        return workout(type, minutes, Instant.parse(start), Instant.parse(end));
    }

    public static ActivityPayload workout(
            WorkoutType type,
            Integer minutes,
            BigDecimal calories,
            BigDecimal distanceMeters,
            Instant start,
            Instant end
    ) {
        return new ActivityPayload(ActivityType.WORKOUT, type, minutes, calories, distanceMeters,
                null, null, null, start, end);
    }

    public static ActivityPayload sleep(int minutes, Integer score, Instant start, Instant end) {
        return new ActivityPayload(ActivityType.SLEEP, null, null, null, null, null, minutes, score, start, end);
    }

    public static ActivityPayload sleep(int minutes, Integer score, String start, String end) {
        return sleep(minutes, score, Instant.parse(start), Instant.parse(end));
    }

    /** 지정한 날짜(UTC)의 "HH:mm"~"HH:mm" 구간 운동. */
    public static ActivityPayload workoutOn(LocalDate date, WorkoutType type, Integer minutes, String start, String end) {
        return workout(type, minutes, date + "T" + start + ":00Z", date + "T" + end + ":00Z");
    }
}
