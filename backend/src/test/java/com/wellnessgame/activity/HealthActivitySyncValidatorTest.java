package com.wellnessgame.activity;

import com.wellnessgame.api.HealthActivitySyncRequest;
import com.wellnessgame.api.HealthActivitySyncRequest.ActivityPayload;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HealthActivitySyncValidatorTest {
    private static final Instant NOW = Instant.parse("2026-06-17T12:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 6, 17);

    private final HealthActivitySyncValidator validator =
            new HealthActivitySyncValidator(Clock.fixed(NOW, ZoneOffset.UTC));

    // ---- 날짜: 서버 기준 오늘 ±1일 ----

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 1})
    void acceptsDateWithinOneDayOfToday(int offsetDays) {
        assertValid(request(TODAY.plusDays(offsetDays), steps(1_000)));
    }

    @ParameterizedTest
    @ValueSource(ints = {-2, 2})
    void rejectsDateOutsideOneDayOfToday(int offsetDays) {
        assertRejected(request(TODAY.plusDays(offsetDays), steps(1_000)),
                "동기화 날짜는 오늘 기준 ±1일 이내여야 합니다.");
    }

    // ---- 미래 시각 (5분 허용) ----

    @Test
    void acceptsEndTimeWithinClockSkewTolerance() {
        Instant end = NOW.plus(Duration.ofMinutes(5));
        assertValid(request(TODAY, workout(30, end.minus(Duration.ofMinutes(30)), end)));
    }

    @Test
    void rejectsEndTimeBeyondClockSkewTolerance() {
        Instant end = NOW.plus(Duration.ofMinutes(5)).plusSeconds(1);
        assertRejected(request(TODAY, workout(30, end.minus(Duration.ofMinutes(30)), end)),
                "활동 시간은 현재 시각 이후일 수 없습니다.");
    }

    @Test
    void rejectsFutureStartTime() {
        Instant start = NOW.plus(Duration.ofMinutes(6));
        assertRejected(request(TODAY, sleep(10, null, start, start.plus(Duration.ofMinutes(10)))),
                "활동 시간은 현재 시각 이후일 수 없습니다.");
    }

    // ---- 분 값 vs 구간 길이 (1분 허용) ----

    @Test
    void acceptsWorkoutDurationUpToSpanPlusOneMinute() {
        assertValid(request(TODAY, workout(31, minutesBeforeNow(30), NOW)));
    }

    @Test
    void rejectsWorkoutDurationLongerThanSpanPlusOneMinute() {
        assertRejected(request(TODAY, workout(32, minutesBeforeNow(30), NOW)),
                "운동 시간(durationMinutes)이 시작~종료 구간 길이보다 깁니다.");
    }

    @Test
    void acceptsSleepMinutesUpToSpanPlusOneMinute() {
        assertValid(request(TODAY, sleep(451, 90, minutesBeforeNow(450), NOW)));
    }

    @Test
    void rejectsSleepMinutesLongerThanSpanPlusOneMinute() {
        assertRejected(request(TODAY, sleep(452, 90, minutesBeforeNow(450), NOW)),
                "수면 시간(sleepMinutes)이 시작~종료 구간 길이보다 깁니다.");
    }

    // ---- 상한 ----

    @Test
    void stepsUpperBound() {
        assertValid(request(TODAY, steps(100_000)));
        assertRejected(request(TODAY, steps(100_001)), "걸음 수는 0 이상 100000 이하여야 합니다.");
    }

    @Test
    void sleepUpperBoundIsSixteenHours() {
        assertValid(request(TODAY, sleep(960, null, minutesBeforeNow(1_000), NOW)));
        assertRejected(request(TODAY, sleep(961, null, minutesBeforeNow(1_000), NOW)),
                "수면 시간은 0분 이상 960분 이하여야 합니다.");
    }

    @Test
    void workoutSpanUpperBoundIsTwelveHours() {
        assertValid(request(TODAY, workout(720, minutesBeforeNow(720), NOW)));
        assertRejected(request(TODAY, workout(60, minutesBeforeNow(721), NOW)),
                "운동 한 건은 최대 12시간까지 기록할 수 있습니다.");
    }

    @Test
    void workoutDurationUpperBoundIsTwelveHours() {
        assertRejected(request(TODAY, workout(721, minutesBeforeNow(720), NOW)),
                "운동 시간은 0분 이상 720분 이하여야 합니다.");
    }

    @Test
    void caloriesUpperBound() {
        assertValid(request(TODAY, workout(30, new BigDecimal("10000"), null)));
        assertRejected(request(TODAY, workout(30, new BigDecimal("10000.01"), null)),
                "칼로리는 0 이상 10000 이하여야 합니다.");
    }

    @Test
    void distanceUpperBound() {
        assertValid(request(TODAY, workout(30, null, new BigDecimal("500000"))));
        assertRejected(request(TODAY, workout(30, null, new BigDecimal("500001"))),
                "거리는 0 이상 500000m 이하여야 합니다.");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 100})
    void acceptsSleepScoreWithinRange(int score) {
        assertValid(request(TODAY, sleep(450, score, minutesBeforeNow(450), NOW)));
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 101})
    void rejectsSleepScoreOutOfRange(int score) {
        assertRejected(request(TODAY, sleep(450, score, minutesBeforeNow(450), NOW)),
                "수면 점수는 0 이상 100 이하여야 합니다.");
    }

    // ---- 요청 크기 ----

    @Test
    void activitiesCountUpperBoundIsFifty() {
        assertValid(request(TODAY, Collections.nCopies(50, steps(1_000))));
        assertRejected(request(TODAY, Collections.nCopies(51, steps(1_000))),
                "한 번에 최대 50개의 활동까지 동기화할 수 있습니다.");
    }

    // ---- helpers ----

    private void assertValid(HealthActivitySyncRequest request) {
        assertThatCode(() -> validator.validate(request)).doesNotThrowAnyException();
    }

    private void assertRejected(HealthActivitySyncRequest request, String message) {
        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(message);
    }

    private static Instant minutesBeforeNow(long minutes) {
        return NOW.minus(Duration.ofMinutes(minutes));
    }

    private static HealthActivitySyncRequest request(LocalDate date, ActivityPayload activity) {
        return request(date, List.of(activity));
    }

    private static HealthActivitySyncRequest request(LocalDate date, List<ActivityPayload> activities) {
        return new HealthActivitySyncRequest("validator-user", date, activities);
    }

    private static ActivityPayload steps(int steps) {
        return new ActivityPayload(ActivityType.STEPS, null, null, null, null, steps, null, null, null, null);
    }

    private static ActivityPayload workout(int minutes, Instant start, Instant end) {
        return new ActivityPayload(ActivityType.WORKOUT, WorkoutType.RUNNING, minutes, null, null,
                null, null, null, start, end);
    }

    private static ActivityPayload workout(int minutes, BigDecimal calories, BigDecimal distance) {
        return new ActivityPayload(ActivityType.WORKOUT, WorkoutType.RUNNING, minutes, calories, distance,
                null, null, null, minutesBeforeNow(minutes), NOW);
    }

    private static ActivityPayload sleep(int minutes, Integer score, Instant start, Instant end) {
        return new ActivityPayload(ActivityType.SLEEP, null, null, null, null, null, minutes, score, start, end);
    }
}
