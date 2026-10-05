package com.wellnessgame.activity;

import com.wellnessgame.api.HealthActivitySyncRequest;
import com.wellnessgame.api.HealthActivitySyncRequest.ActivityPayload;
import com.wellnessgame.i18n.LocalizedMessage;
import com.wellnessgame.i18n.Messages;
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
 *       사유(메시지 키 + 인자)를 반환하며 서비스는 해당 항목만 건너뛴다.</li>
 * </ul>
 * 날짜 계산은 주입된 Clock 의 시간대(app.timezone)를 따른다. 문구는 messages*.properties 의 sync.* 키를 쓴다.
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
        require(request.activities() != null && !request.activities().isEmpty(), "sync.error.activities-empty");
        require(request.activities().size() <= MAX_ACTIVITIES_PER_REQUEST,
                "sync.error.too-many-activities", MAX_ACTIVITIES_PER_REQUEST);
        validateDate(request.date());
        request.activities().forEach(this::validateStructure);
    }

    private void validateDate(LocalDate date) {
        require(date != null, "sync.error.date-required");
        LocalDate today = LocalDate.now(clock);
        require(!date.isBefore(today.minusDays(SYNC_DATE_TOLERANCE_DAYS))
                        && !date.isAfter(today.plusDays(SYNC_DATE_TOLERANCE_DAYS)),
                "sync.error.date-out-of-range", SYNC_DATE_TOLERANCE_DAYS);
    }

    private void validateStructure(ActivityPayload activity) {
        require(activity.type() != null, "sync.error.type-required");
        switch (activity.type()) {
            case STEPS -> require(activity.steps() != null, "sync.error.steps-required");
            case WORKOUT -> {
                require(activity.startedAt() != null && activity.endedAt() != null,
                        "sync.error.workout-times-required");
                requireEndNotBeforeStart(activity);
            }
            case SLEEP -> {
                require(activity.sleepMinutes() != null && activity.startedAt() != null && activity.endedAt() != null,
                        "sync.error.sleep-fields-required");
                requireEndNotBeforeStart(activity);
            }
        }
    }

    private void requireEndNotBeforeStart(ActivityPayload activity) {
        require(!activity.endedAt().isBefore(activity.startedAt()), "sync.error.end-before-start");
    }

    // ---- 값 범위 검증 (해당 항목만 건너뛰기) ----

    /**
     * 구조 검증을 통과한 활동의 값 범위 위반 사유(메시지 키 + 인자). 문제가 없으면 empty.
     */
    public Optional<LocalizedMessage> rejection(LocalDate date, ActivityPayload activity) {
        LocalizedMessage timeReason = timeReason(date, activity);
        if (timeReason != null) {
            return Optional.of(timeReason);
        }
        return Optional.ofNullable(switch (activity.type()) {
            case STEPS -> withinRange(activity.steps(), 0, MAX_STEPS)
                    ? null
                    : LocalizedMessage.of("sync.reason.steps-range", MAX_STEPS);
            case WORKOUT -> workoutReason(activity);
            case SLEEP -> sleepReason(activity);
        });
    }

    /**
     * {@link #rejection} 을 현재 로케일 문구로 만든 값.
     */
    public Optional<String> rejectionReason(LocalDate date, ActivityPayload activity) {
        return rejection(date, activity).map(Messages::get);
    }

    /**
     * 미래 시각(5분 허용)과 날짜 창: startedAt 은 date 전날 00:00 이후, endedAt 은 date 다음 날 24:00 이전.
     */
    private LocalizedMessage timeReason(LocalDate date, ActivityPayload activity) {
        Instant latestAllowed = clock.instant().plus(FUTURE_TOLERANCE);
        if (isAfter(activity.startedAt(), latestAllowed) || isAfter(activity.endedAt(), latestAllowed)) {
            return LocalizedMessage.of("sync.reason.future-time");
        }
        ZoneId zone = clock.getZone();
        Instant windowStart = date.minusDays(1).atStartOfDay(zone).toInstant();
        Instant windowEnd = date.plusDays(2).atStartOfDay(zone).toInstant();
        if ((activity.startedAt() != null && activity.startedAt().isBefore(windowStart))
                || isAfter(activity.endedAt(), windowEnd)) {
            return LocalizedMessage.of("sync.reason.outside-date-window");
        }
        return null;
    }

    private LocalizedMessage workoutReason(ActivityPayload activity) {
        if (!activity.startedAt().isBefore(activity.endedAt())) {
            return LocalizedMessage.of("sync.reason.workout-end-not-after-start");
        }
        Duration span = Duration.between(activity.startedAt(), activity.endedAt());
        long spanMinutes = span.toMinutes();
        if (spanMinutes > MAX_WORKOUT_MINUTES) {
            return LocalizedMessage.of("sync.reason.workout-too-long", MAX_WORKOUT_MINUTES / 60);
        }
        int durationMinutes = activity.durationMinutes() == null
                ? (int) spanMinutes
                : activity.durationMinutes();
        LocalizedMessage minutesReason = minutesReason(durationMinutes, MAX_WORKOUT_MINUTES, spanMinutes,
                "sync.reason.workout-minutes-range", "sync.reason.workout-minutes-exceed-span");
        if (minutesReason != null) {
            return minutesReason;
        }
        if (!withinDecimal(activity.calories(), MAX_CALORIES)) {
            return LocalizedMessage.of("sync.reason.calories-range", MAX_CALORIES);
        }
        if (activity.calories() != null && exceedsCaloriesPerMinute(activity.calories(), span)) {
            return LocalizedMessage.of("sync.reason.calories-per-minute", MAX_CALORIES_PER_MINUTE);
        }
        if (!withinDecimal(activity.distanceMeters(), MAX_DISTANCE_METERS)) {
            return LocalizedMessage.of("sync.reason.distance-range", MAX_DISTANCE_METERS);
        }
        return null;
    }

    private LocalizedMessage sleepReason(ActivityPayload activity) {
        long spanMinutes = Duration.between(activity.startedAt(), activity.endedAt()).toMinutes();
        LocalizedMessage minutesReason = minutesReason(activity.sleepMinutes(), MAX_SLEEP_MINUTES, spanMinutes,
                "sync.reason.sleep-minutes-range", "sync.reason.sleep-minutes-exceed-span");
        if (minutesReason != null) {
            return minutesReason;
        }
        if (activity.sleepScore() != null && !withinRange(activity.sleepScore(), MIN_SLEEP_SCORE, MAX_SLEEP_SCORE)) {
            return LocalizedMessage.of("sync.reason.sleep-score-range", MIN_SLEEP_SCORE, MAX_SLEEP_SCORE);
        }
        return null;
    }

    /**
     * 분 값의 범위(0~max)와 구간 길이(반올림 오차 1분 허용) 검증.
     */
    private LocalizedMessage minutesReason(int minutes, int max, long spanMinutes, String rangeKey, String spanKey) {
        if (!withinRange(minutes, 0, max)) {
            return LocalizedMessage.of(rangeKey, max);
        }
        if (minutes > spanMinutes + MINUTE_ROUNDING_TOLERANCE) {
            return LocalizedMessage.of(spanKey);
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

    private static void require(boolean condition, String messageCode, Object... args) {
        if (!condition) {
            throw new IllegalArgumentException(Messages.get(messageCode, args));
        }
    }
}
