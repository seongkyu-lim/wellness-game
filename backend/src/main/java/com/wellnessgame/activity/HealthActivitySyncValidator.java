package com.wellnessgame.activity;

import com.wellnessgame.api.HealthActivitySyncRequest;
import com.wellnessgame.api.HealthActivitySyncRequest.ActivityPayload;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;

/**
 * 동기화 요청 검증. 두 단계로 나뉜다.
 * <ul>
 *   <li>{@link #validate}: 구조 오류(필수 필드 누락, 종료 < 시작, 활동 개수 초과, 동기화 날짜가 오늘 ±1일 밖).
 *       IllegalArgumentException 을 던지며 요청 전체가 400 으로 거부된다.</li>
 *   <li>{@link #rejectionReason}: 값 범위 위반(상한 초과, 분 값이 구간보다 김, 미래 시각, 날짜 창 이탈 등).
 *       사유를 반환하며 서비스는 해당 항목만 건너뛴다.</li>
 * </ul>
 * 날짜 계산은 주입된 Clock 의 시간대(app.timezone)를 따른다.
 */
@Component
public class HealthActivitySyncValidator {
    public static final int MAX_ACTIVITIES_PER_REQUEST = 50;
    public static final int SYNC_DATE_TOLERANCE_DAYS = 1;
    public static final Duration FUTURE_TOLERANCE = Duration.ofMinutes(5);
    public static final int MINUTE_ROUNDING_TOLERANCE = 1;

    public static final int MAX_STEPS = 100_000;
    public static final int MAX_SLEEP_MINUTES = 16 * 60;
    public static final int MAX_WORKOUT_MINUTES = 12 * 60;
    public static final BigDecimal MAX_CALORIES = BigDecimal.valueOf(10_000);
    public static final int MAX_CALORIES_PER_MINUTE = 20;
    public static final BigDecimal MAX_DISTANCE_METERS = BigDecimal.valueOf(500_000);
    public static final int MIN_SLEEP_SCORE = 0;
    public static final int MAX_SLEEP_SCORE = 100;

    private final Clock clock;

    public HealthActivitySyncValidator(Clock clock) {
        this.clock = clock;
    }

    // ---- 구조 검증 (요청 전체 400) ----

    public void validate(HealthActivitySyncRequest request) {
        require(request.activities() != null && !request.activities().isEmpty(),
                "동기화할 활동이 없습니다.");
        require(request.activities().size() <= MAX_ACTIVITIES_PER_REQUEST,
                "한 번에 최대 " + MAX_ACTIVITIES_PER_REQUEST + "개의 활동까지 동기화할 수 있습니다.");
        validateDate(request.date());
        request.activities().forEach(this::validateStructure);
    }

    private void validateDate(LocalDate date) {
        require(date != null, "동기화 날짜가 필요합니다.");
        LocalDate today = LocalDate.now(clock);
        require(!date.isBefore(today.minusDays(SYNC_DATE_TOLERANCE_DAYS))
                        && !date.isAfter(today.plusDays(SYNC_DATE_TOLERANCE_DAYS)),
                "동기화 날짜는 오늘 기준 ±" + SYNC_DATE_TOLERANCE_DAYS + "일 이내여야 합니다.");
    }

    private void validateStructure(ActivityPayload activity) {
        require(activity.type() != null, "활동 유형이 필요합니다.");
        switch (activity.type()) {
            case STEPS -> require(activity.steps() != null, "STEPS 활동에는 steps가 필요합니다.");
            case WORKOUT -> {
                require(activity.startedAt() != null && activity.endedAt() != null,
                        "WORKOUT 활동에는 startedAt과 endedAt이 필요합니다.");
                requireEndNotBeforeStart(activity);
            }
            case SLEEP -> {
                require(activity.sleepMinutes() != null && activity.startedAt() != null && activity.endedAt() != null,
                        "SLEEP 활동에는 sleepMinutes, startedAt, endedAt이 필요합니다.");
                requireEndNotBeforeStart(activity);
            }
        }
    }

    private void requireEndNotBeforeStart(ActivityPayload activity) {
        require(!activity.endedAt().isBefore(activity.startedAt()),
                "활동 종료 시간은 시작 시간 이후여야 합니다.");
    }

    // ---- 값 범위 검증 (해당 항목만 건너뛰기) ----

    /**
     * 구조 검증을 통과한 활동의 값 범위 위반 사유. 문제가 없으면 empty.
     */
    public Optional<String> rejectionReason(LocalDate date, ActivityPayload activity) {
        String timeReason = timeReason(date, activity);
        if (timeReason != null) {
            return Optional.of(timeReason);
        }
        return Optional.ofNullable(switch (activity.type()) {
            case STEPS -> withinRange(activity.steps(), 0, MAX_STEPS)
                    ? null
                    : "걸음 수는 0 이상 " + MAX_STEPS + " 이하여야 합니다.";
            case WORKOUT -> workoutReason(activity);
            case SLEEP -> sleepReason(activity);
        });
    }

    /**
     * 미래 시각(5분 허용)과 날짜 창: startedAt 은 date 전날 00:00 이후, endedAt 은 date 다음 날 24:00 이전.
     */
    private String timeReason(LocalDate date, ActivityPayload activity) {
        Instant latestAllowed = clock.instant().plus(FUTURE_TOLERANCE);
        if (isAfter(activity.startedAt(), latestAllowed) || isAfter(activity.endedAt(), latestAllowed)) {
            return "활동 시간은 현재 시각 이후일 수 없습니다.";
        }
        ZoneId zone = clock.getZone();
        Instant windowStart = date.minusDays(1).atStartOfDay(zone).toInstant();
        Instant windowEnd = date.plusDays(2).atStartOfDay(zone).toInstant();
        if ((activity.startedAt() != null && activity.startedAt().isBefore(windowStart))
                || isAfter(activity.endedAt(), windowEnd)) {
            return "활동 시간이 동기화 날짜 범위를 벗어났습니다.";
        }
        return null;
    }

    private String workoutReason(ActivityPayload activity) {
        if (!activity.startedAt().isBefore(activity.endedAt())) {
            return "운동 종료 시간은 시작 시간보다 늦어야 합니다.";
        }
        Duration span = Duration.between(activity.startedAt(), activity.endedAt());
        long spanMinutes = span.toMinutes();
        if (spanMinutes > MAX_WORKOUT_MINUTES) {
            return "운동 한 건은 최대 " + MAX_WORKOUT_MINUTES / 60 + "시간까지 기록할 수 있습니다.";
        }
        int durationMinutes = activity.durationMinutes() == null
                ? (int) spanMinutes
                : activity.durationMinutes();
        String minutesReason = minutesReason(durationMinutes, MAX_WORKOUT_MINUTES, spanMinutes,
                "운동 시간", "durationMinutes");
        if (minutesReason != null) {
            return minutesReason;
        }
        if (!withinDecimal(activity.calories(), MAX_CALORIES)) {
            return "칼로리는 0 이상 " + MAX_CALORIES.toPlainString() + " 이하여야 합니다.";
        }
        if (activity.calories() != null && exceedsCaloriesPerMinute(activity.calories(), span)) {
            return "칼로리가 운동 시간 대비 너무 큽니다(분당 최대 " + MAX_CALORIES_PER_MINUTE + "kcal).";
        }
        if (!withinDecimal(activity.distanceMeters(), MAX_DISTANCE_METERS)) {
            return "거리는 0 이상 " + MAX_DISTANCE_METERS.toPlainString() + "m 이하여야 합니다.";
        }
        return null;
    }

    private String sleepReason(ActivityPayload activity) {
        long spanMinutes = Duration.between(activity.startedAt(), activity.endedAt()).toMinutes();
        String minutesReason = minutesReason(activity.sleepMinutes(), MAX_SLEEP_MINUTES, spanMinutes,
                "수면 시간", "sleepMinutes");
        if (minutesReason != null) {
            return minutesReason;
        }
        if (activity.sleepScore() != null && !withinRange(activity.sleepScore(), MIN_SLEEP_SCORE, MAX_SLEEP_SCORE)) {
            return "수면 점수는 " + MIN_SLEEP_SCORE + " 이상 " + MAX_SLEEP_SCORE + " 이하여야 합니다.";
        }
        return null;
    }

    /**
     * 분 값의 범위(0~max)와 구간 길이(반올림 오차 1분 허용) 검증.
     */
    private String minutesReason(int minutes, int max, long spanMinutes, String label, String field) {
        if (!withinRange(minutes, 0, max)) {
            return label + "은 0분 이상 " + max + "분 이하여야 합니다.";
        }
        if (minutes > spanMinutes + MINUTE_ROUNDING_TOLERANCE) {
            return label + "(" + field + ")이 시작~종료 구간 길이보다 깁니다.";
        }
        return null;
    }

    /**
     * calories > 분당 상한 × 구간(초 단위까지 반영).  calories * 60 > 20 * seconds 와 동치.
     */
    private boolean exceedsCaloriesPerMinute(BigDecimal calories, Duration span) {
        BigDecimal limitTimesSixty = BigDecimal.valueOf(MAX_CALORIES_PER_MINUTE).multiply(BigDecimal.valueOf(span.toSeconds()));
        return calories.multiply(BigDecimal.valueOf(60)).compareTo(limitTimesSixty) > 0;
    }

    private static boolean isAfter(Instant time, Instant limit) {
        return time != null && time.isAfter(limit);
    }

    private static boolean withinRange(int value, int min, int max) {
        return value >= min && value <= max;
    }

    private static boolean withinDecimal(BigDecimal value, BigDecimal max) {
        return value == null || (value.signum() >= 0 && value.compareTo(max) <= 0);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
