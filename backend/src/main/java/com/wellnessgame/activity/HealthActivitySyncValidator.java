package com.wellnessgame.activity;

import com.wellnessgame.error.BadRequestException;
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
 *       BadRequestException 을 던지며 요청 전체가 400 으로 거부된다.</li>
 *   <li>{@link #rejection}: 값 범위 위반(상한 초과, 분 값이 구간보다 김, 미래 시각, 날짜 창 이탈,
 *       미래 날짜 걸음 수, 깬 날짜가 요청 date ±1일 밖인 수면 등).
 *       사유(메시지 키 + 인자)를 반환하며 서비스는 해당 항목만 건너뛴다.</li>
 * </ul>
 * 날짜 계산은 주입된 Clock 의 시간대(app.timezone)를 따른다. 문구는 messages*.properties 의 sync.* 키를 쓴다.
 */
@Component
public class HealthActivitySyncValidator {
    public static final int MAX_ACTIVITIES_PER_REQUEST = 50;
    public static final int SYNC_DATE_TOLERANCE_DAYS = 1;
    public static final int SLEEP_WAKE_DATE_TOLERANCE_DAYS = 1;
    public static final Duration FUTURE_TOLERANCE = Duration.ofMinutes(5);

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
            case STEPS -> stepsReason(date, activity);
            case WORKOUT -> workoutReason(activity);
            case SLEEP -> sleepReason(date, activity);
        });
    }

    /**
     * 미래 시각(5분 허용)과 날짜 창: startedAt 은 date 전날 00:00 이후, endedAt 은 date 다음 날 24:00 이전.
     */
    private LocalizedMessage timeReason(LocalDate date, ActivityPayload activity) {
        Instant latestAllowed = clock.instant().plus(FUTURE_TOLERANCE);
        if (isAfter(activity.startedAt(), latestAllowed) || isAfter(activity.endedAt(), latestAllowed)) {
            return new LocalizedMessage("sync.reason.future-time");
        }
        ZoneId zone = clock.getZone();
        Instant windowStart = date.minusDays(1).atStartOfDay(zone).toInstant();
        Instant windowEnd = date.plusDays(2).atStartOfDay(zone).toInstant();
        if ((activity.startedAt() != null && activity.startedAt().isBefore(windowStart))
                || isAfter(activity.endedAt(), windowEnd)) {
            return new LocalizedMessage("sync.reason.outside-date-window");
        }
        return null;
    }

    /**
     * 걸음 수 범위와 날짜: 서버 기준 오늘보다 뒤 날짜(내일)의 걸음 수는 받지 않는다.
     */
    private LocalizedMessage stepsReason(LocalDate date, ActivityPayload activity) {
        if (date.isAfter(LocalDate.now(clock))) {
            return new LocalizedMessage("sync.reason.steps-future-date");
        }
        return withinRange(activity.steps(), 0, MAX_STEPS)
                ? null
                : new LocalizedMessage("sync.reason.steps-range", MAX_STEPS);
    }

    private LocalizedMessage workoutReason(ActivityPayload activity) {
        if (!activity.startedAt().isBefore(activity.endedAt())) {
            return new LocalizedMessage("sync.reason.workout-end-not-after-start");
        }
        Duration span = Duration.between(activity.startedAt(), activity.endedAt());
        if (span.toMinutes() > MAX_WORKOUT_MINUTES) {
            return new LocalizedMessage("sync.reason.workout-too-long", MAX_WORKOUT_MINUTES / 60);
        }
        long spanMinutes = spanMinutes(activity.startedAt(), activity.endedAt());
        int durationMinutes = activity.durationMinutes() == null
                ? (int) spanMinutes
                : activity.durationMinutes();
        LocalizedMessage minutesReason = minutesReason(durationMinutes, MAX_WORKOUT_MINUTES, spanMinutes,
                "sync.reason.workout-minutes-range", "sync.reason.workout-minutes-exceed-span");
        if (minutesReason != null) {
            return minutesReason;
        }
        if (!withinDecimal(activity.calories(), MAX_CALORIES)) {
            return new LocalizedMessage("sync.reason.calories-range", MAX_CALORIES);
        }
        if (activity.calories() != null && exceedsCaloriesPerMinute(activity.calories(), span)) {
            return new LocalizedMessage("sync.reason.calories-per-minute", MAX_CALORIES_PER_MINUTE);
        }
        if (!withinDecimal(activity.distanceMeters(), MAX_DISTANCE_METERS)) {
            return new LocalizedMessage("sync.reason.distance-range", MAX_DISTANCE_METERS);
        }
        return null;
    }

    /**
     * 수면의 깬 시각(endedAt)은 서버 현지 날짜 기준 요청 date ±{@value #SLEEP_WAKE_DATE_TOLERANCE_DAYS}일 안이어야 한다.
     * 요청 date 는 기기 현지 날짜라 서버 시간대(KST)와 하루 어긋날 수 있다(예: 미국 서부 08:00 기상 = KST 다음 날 00:00).
     * 같은 수면을 날짜만 바꿔 다시 받는 것은 서비스가 다른 날짜 수면 로그와의 겹침으로 막는다.
     */
    private LocalizedMessage sleepReason(LocalDate date, ActivityPayload activity) {
        LocalDate wakeDate = LocalDate.ofInstant(activity.endedAt(), clock.getZone());
        if (wakeDate.isBefore(date.minusDays(SLEEP_WAKE_DATE_TOLERANCE_DAYS))
                || wakeDate.isAfter(date.plusDays(SLEEP_WAKE_DATE_TOLERANCE_DAYS))) {
            return new LocalizedMessage("sync.reason.sleep-date-mismatch", wakeDate.toString());
        }
        long spanMinutes = spanMinutes(activity.startedAt(), activity.endedAt());
        LocalizedMessage minutesReason = minutesReason(activity.sleepMinutes(), MAX_SLEEP_MINUTES, spanMinutes,
                "sync.reason.sleep-minutes-range", "sync.reason.sleep-minutes-exceed-span");
        if (minutesReason != null) {
            return minutesReason;
        }
        if (activity.sleepScore() != null && !withinRange(activity.sleepScore(), MIN_SLEEP_SCORE, MAX_SLEEP_SCORE)) {
            return new LocalizedMessage("sync.reason.sleep-score-range", MIN_SLEEP_SCORE, MAX_SLEEP_SCORE);
        }
        return null;
    }

    /**
     * 시작~종료 구간 길이(분). 초 단위 구간을 분으로 반올림(30초 이상 올림)한다.
     * 클라이언트(iOS·Android)도 활동 시간·수면 시간을 같은 방식(half-up)으로 반올림해 보내며, 활동 시간은 구간 이하이므로
     * 반올림한 값도 이 값 이하다. 따라서 별도 허용 오차 없이 {@code 분 ≤ spanMinutes} 로 검증한다.
     * (예: 1초 구간은 0분이라 1분을 주장할 수 없다.)
     */
    public static long spanMinutes(Instant startedAt, Instant endedAt) {
        return (Duration.between(startedAt, endedAt).toSeconds() + 30) / 60;
    }

    /**
     * 분 값의 범위(0~max)와 구간 길이(반올림한 분, 허용 오차 없음) 검증.
     */
    private LocalizedMessage minutesReason(int minutes, int max, long spanMinutes, String rangeKey, String spanKey) {
        if (!withinRange(minutes, 0, max)) {
            return new LocalizedMessage(rangeKey, max);
        }
        if (minutes > spanMinutes) {
            return new LocalizedMessage(spanKey);
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
            throw new BadRequestException(Messages.get(messageCode, args));
        }
    }
}
