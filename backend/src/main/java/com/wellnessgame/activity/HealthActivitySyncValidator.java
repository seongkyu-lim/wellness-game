package com.wellnessgame.activity;

import com.wellnessgame.api.HealthActivitySyncRequest;
import com.wellnessgame.api.HealthActivitySyncRequest.ActivityPayload;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 저장소 조회 없이 판단 가능한 동기화 요청 검증(날짜·미래 시각·값 범위·분 값 vs 구간 길이).
 * 위반 시 IllegalArgumentException 을 던지며 ApiExceptionHandler 가 400 으로 변환한다.
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
    public static final BigDecimal MAX_DISTANCE_METERS = BigDecimal.valueOf(500_000);
    public static final int MIN_SLEEP_SCORE = 0;
    public static final int MAX_SLEEP_SCORE = 100;

    private final Clock clock;

    public HealthActivitySyncValidator(Clock clock) {
        this.clock = clock;
    }

    public void validate(HealthActivitySyncRequest request) {
        require(request.activities() != null && !request.activities().isEmpty(),
                "동기화할 활동이 없습니다.");
        require(request.activities().size() <= MAX_ACTIVITIES_PER_REQUEST,
                "한 번에 최대 " + MAX_ACTIVITIES_PER_REQUEST + "개의 활동까지 동기화할 수 있습니다.");
        validateDate(request.date());

        Instant latestAllowed = clock.instant().plus(FUTURE_TOLERANCE);
        request.activities().forEach(activity -> validatePayload(activity, latestAllowed));
    }

    private void validateDate(LocalDate date) {
        require(date != null, "동기화 날짜가 필요합니다.");
        LocalDate today = LocalDate.now(clock);
        require(!date.isBefore(today.minusDays(SYNC_DATE_TOLERANCE_DAYS))
                        && !date.isAfter(today.plusDays(SYNC_DATE_TOLERANCE_DAYS)),
                "동기화 날짜는 오늘 기준 ±" + SYNC_DATE_TOLERANCE_DAYS + "일 이내여야 합니다.");
    }

    private void validatePayload(ActivityPayload activity, Instant latestAllowed) {
        require(activity.type() != null, "활동 유형이 필요합니다.");
        requireNotFuture(activity.startedAt(), latestAllowed);
        requireNotFuture(activity.endedAt(), latestAllowed);

        switch (activity.type()) {
            case STEPS -> {
                require(activity.steps() != null, "STEPS 활동에는 steps가 필요합니다.");
                requireRange(activity.steps(), 0, MAX_STEPS, "걸음 수는 0 이상 " + MAX_STEPS + " 이하여야 합니다.");
            }
            case WORKOUT -> {
                require(activity.startedAt() != null && activity.endedAt() != null,
                        "WORKOUT 활동에는 startedAt과 endedAt이 필요합니다.");
                long spanMinutes = requireValidTimeRange(activity);
                require(spanMinutes <= MAX_WORKOUT_MINUTES,
                        "운동 한 건은 최대 " + MAX_WORKOUT_MINUTES / 60 + "시간까지 기록할 수 있습니다.");
                if (activity.durationMinutes() != null) {
                    requireRange(activity.durationMinutes(), 0, MAX_WORKOUT_MINUTES,
                            "운동 시간은 0분 이상 " + MAX_WORKOUT_MINUTES + "분 이하여야 합니다.");
                    require(activity.durationMinutes() <= spanMinutes + MINUTE_ROUNDING_TOLERANCE,
                            "운동 시간(durationMinutes)이 시작~종료 구간 길이보다 깁니다.");
                }
                requireDecimalRange(activity.calories(), MAX_CALORIES,
                        "칼로리는 0 이상 " + MAX_CALORIES.toPlainString() + " 이하여야 합니다.");
                requireDecimalRange(activity.distanceMeters(), MAX_DISTANCE_METERS,
                        "거리는 0 이상 " + MAX_DISTANCE_METERS.toPlainString() + "m 이하여야 합니다.");
            }
            case SLEEP -> {
                require(activity.sleepMinutes() != null && activity.startedAt() != null && activity.endedAt() != null,
                        "SLEEP 활동에는 sleepMinutes, startedAt, endedAt이 필요합니다.");
                long spanMinutes = requireValidTimeRange(activity);
                requireRange(activity.sleepMinutes(), 0, MAX_SLEEP_MINUTES,
                        "수면 시간은 0분 이상 " + MAX_SLEEP_MINUTES + "분 이하여야 합니다.");
                require(activity.sleepMinutes() <= spanMinutes + MINUTE_ROUNDING_TOLERANCE,
                        "수면 시간(sleepMinutes)이 시작~종료 구간 길이보다 깁니다.");
                if (activity.sleepScore() != null) {
                    requireRange(activity.sleepScore(), MIN_SLEEP_SCORE, MAX_SLEEP_SCORE,
                            "수면 점수는 " + MIN_SLEEP_SCORE + " 이상 " + MAX_SLEEP_SCORE + " 이하여야 합니다.");
                }
            }
        }
    }

    private void requireNotFuture(Instant time, Instant latestAllowed) {
        if (time != null) {
            require(!time.isAfter(latestAllowed), "활동 시간은 현재 시각 이후일 수 없습니다.");
        }
    }

    /**
     * 시작/종료 순서를 검증하고 구간 길이(분, 내림)를 반환한다.
     */
    private long requireValidTimeRange(ActivityPayload activity) {
        require(!activity.endedAt().isBefore(activity.startedAt()),
                "활동 종료 시간은 시작 시간 이후여야 합니다.");
        return Duration.between(activity.startedAt(), activity.endedAt()).toMinutes();
    }

    private void requireRange(int value, int min, int max, String message) {
        require(value >= min && value <= max, message);
    }

    private void requireDecimalRange(BigDecimal value, BigDecimal max, String message) {
        if (value != null) {
            require(value.signum() >= 0 && value.compareTo(max) <= 0, message);
        }
    }

    private void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
