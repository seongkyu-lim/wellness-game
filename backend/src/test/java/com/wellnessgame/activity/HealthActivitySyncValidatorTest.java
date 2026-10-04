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
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;

import static com.wellnessgame.support.ActivityPayloads.sleep;
import static com.wellnessgame.support.ActivityPayloads.steps;
import static com.wellnessgame.support.ActivityPayloads.workout;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HealthActivitySyncValidatorTest {
    private static final Instant NOW = Instant.parse("2026-06-17T12:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 6, 17);

    private final HealthActivitySyncValidator validator =
            new HealthActivitySyncValidator(Clock.fixed(NOW, ZoneOffset.UTC));

    // ===== 구조 검증: 요청 전체 400 =====

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 1})
    void acceptsDateWithinOneDayOfToday(int offsetDays) {
        assertStructureValid(request(TODAY.plusDays(offsetDays), steps(1_000)));
    }

    @ParameterizedTest
    @ValueSource(ints = {-2, 2})
    void rejectsDateOutsideOneDayOfToday(int offsetDays) {
        assertStructureRejected(request(TODAY.plusDays(offsetDays), steps(1_000)),
                "동기화 날짜는 오늘 기준 ±1일 이내여야 합니다.");
    }

    @Test
    void todayIsComputedInClockZone() {
        // UTC 2026-06-17T16:00 은 서울 기준 06-18 01:00 → 서울 기준으로는 06-19 가 +1일
        HealthActivitySyncValidator seoul = new HealthActivitySyncValidator(
                Clock.fixed(Instant.parse("2026-06-17T16:00:00Z"), ZoneId.of("Asia/Seoul")));
        assertThatCode(() -> seoul.validate(request(LocalDate.of(2026, 6, 19), steps(1))))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> seoul.validate(request(LocalDate.of(2026, 6, 16), steps(1))))
                .hasMessage("동기화 날짜는 오늘 기준 ±1일 이내여야 합니다.");
    }

    @Test
    void activitiesCountUpperBoundIsFifty() {
        assertStructureValid(request(TODAY, Collections.nCopies(50, steps(1_000))));
        assertStructureRejected(request(TODAY, Collections.nCopies(51, steps(1_000))),
                "한 번에 최대 50개의 활동까지 동기화할 수 있습니다.");
    }

    @Test
    void missingRequiredFieldIsStructuralError() {
        assertStructureRejected(request(TODAY, workout(WorkoutType.RUNNING, 30, (Instant) null, NOW)),
                "WORKOUT 활동에는 startedAt과 endedAt이 필요합니다.");
    }

    @Test
    void endBeforeStartIsStructuralError() {
        assertStructureRejected(request(TODAY, sleep(10, null, NOW, minutesBeforeNow(10))),
                "활동 종료 시간은 시작 시간 이후여야 합니다.");
    }

    // ===== 값 범위: 해당 항목만 건너뛰기 =====

    @Test
    void acceptsEndTimeWithinClockSkewTolerance() {
        Instant end = NOW.plus(Duration.ofMinutes(5));
        assertAccepted(workout(WorkoutType.RUNNING, 30, end.minus(Duration.ofMinutes(30)), end));
    }

    @Test
    void rejectsEndTimeBeyondClockSkewTolerance() {
        Instant end = NOW.plus(Duration.ofMinutes(5)).plusSeconds(1);
        assertReason(workout(WorkoutType.RUNNING, 30, end.minus(Duration.ofMinutes(30)), end),
                "활동 시간은 현재 시각 이후일 수 없습니다.");
    }

    @Test
    void startMustNotBeBeforePreviousDayMidnight() {
        Instant windowStart = Instant.parse("2026-06-16T00:00:00Z");
        assertAccepted(sleep(400, 90, windowStart, windowStart.plus(Duration.ofMinutes(400))));
        assertReason(sleep(400, 90, windowStart.minusSeconds(1), windowStart.plus(Duration.ofMinutes(400))),
                "활동 시간이 동기화 날짜 범위를 벗어났습니다.");
    }

    @Test
    void endMustNotBeAfterNextDayMidnight() {
        // 동기화 날짜 06-16 의 창 끝은 다음 날(06-17) 24:00 = 06-18 00:00.
        LocalDate yesterday = TODAY.minusDays(1);
        Instant windowEnd = Instant.parse("2026-06-18T00:00:00Z");
        HealthActivitySyncValidator later = new HealthActivitySyncValidator(
                Clock.fixed(Instant.parse("2026-06-18T00:30:00Z"), ZoneOffset.UTC));
        assertThat(later.rejectionReason(yesterday,
                workout(WorkoutType.RUNNING, 30, windowEnd.minus(Duration.ofMinutes(30)), windowEnd))).isEmpty();
        assertThat(later.rejectionReason(yesterday,
                workout(WorkoutType.RUNNING, 30, windowEnd.minus(Duration.ofMinutes(29)), windowEnd.plusSeconds(60))))
                .contains("활동 시간이 동기화 날짜 범위를 벗어났습니다.");
    }

    @Test
    void workoutRequiresPositiveSpan() {
        assertReason(workout(WorkoutType.RUNNING, 0, NOW, NOW), "운동 종료 시간은 시작 시간보다 늦어야 합니다.");
    }

    @Test
    void acceptsWorkoutDurationUpToSpanPlusOneMinute() {
        assertAccepted(workout(WorkoutType.RUNNING, 31, minutesBeforeNow(30), NOW));
    }

    @Test
    void rejectsWorkoutDurationLongerThanSpanPlusOneMinute() {
        assertReason(workout(WorkoutType.RUNNING, 32, minutesBeforeNow(30), NOW),
                "운동 시간(durationMinutes)이 시작~종료 구간 길이보다 깁니다.");
    }

    @Test
    void acceptsSleepMinutesUpToSpanPlusOneMinute() {
        assertAccepted(sleep(451, 90, minutesBeforeNow(450), NOW));
    }

    @Test
    void rejectsSleepMinutesLongerThanSpanPlusOneMinute() {
        assertReason(sleep(452, 90, minutesBeforeNow(450), NOW),
                "수면 시간(sleepMinutes)이 시작~종료 구간 길이보다 깁니다.");
    }

    @Test
    void stepsUpperBound() {
        assertAccepted(steps(100_000));
        assertReason(steps(100_001), "걸음 수는 0 이상 100000 이하여야 합니다.");
    }

    @Test
    void sleepUpperBoundIsSixteenHours() {
        assertAccepted(sleep(960, null, minutesBeforeNow(1_000), NOW));
        assertReason(sleep(961, null, minutesBeforeNow(1_000), NOW), "수면 시간은 0분 이상 960분 이하여야 합니다.");
    }

    @Test
    void workoutSpanUpperBoundIsTwelveHours() {
        assertAccepted(workout(WorkoutType.RUNNING, 720, minutesBeforeNow(720), NOW));
        assertReason(workout(WorkoutType.RUNNING, 60, minutesBeforeNow(721), NOW),
                "운동 한 건은 최대 12시간까지 기록할 수 있습니다.");
    }

    @Test
    void workoutDurationUpperBoundIsTwelveHours() {
        assertReason(workout(WorkoutType.RUNNING, 721, minutesBeforeNow(720), NOW),
                "운동 시간은 0분 이상 720분 이하여야 합니다.");
    }

    @Test
    void nullDurationIsCheckedAsSpanLength() {
        assertAccepted(workout(WorkoutType.RUNNING, null, minutesBeforeNow(30), NOW));
    }

    @Test
    void caloriesAbsoluteUpperBound() {
        assertAccepted(workoutWithCalories(600, "10000"));
        assertReason(workoutWithCalories(600, "10000.01"), "칼로리는 0 이상 10000 이하여야 합니다.");
    }

    @Test
    void caloriesPerMinuteUpperBound() {
        assertAccepted(workoutWithCalories(30, "600"));
        assertReason(workoutWithCalories(30, "600.01"), "칼로리가 운동 시간 대비 너무 큽니다(분당 최대 20kcal).");
    }

    @Test
    void distanceUpperBound() {
        assertAccepted(workout(WorkoutType.CYCLING, 600, null, new BigDecimal("500000"), minutesBeforeNow(600), NOW));
        assertReason(workout(WorkoutType.CYCLING, 600, null, new BigDecimal("500001"), minutesBeforeNow(600), NOW),
                "거리는 0 이상 500000m 이하여야 합니다.");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 100})
    void acceptsSleepScoreWithinRange(int score) {
        assertAccepted(sleep(450, score, minutesBeforeNow(450), NOW));
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 101})
    void rejectsSleepScoreOutOfRange(int score) {
        assertReason(sleep(450, score, minutesBeforeNow(450), NOW), "수면 점수는 0 이상 100 이하여야 합니다.");
    }

    // ---- helpers ----

    private void assertStructureValid(HealthActivitySyncRequest request) {
        assertThatCode(() -> validator.validate(request)).doesNotThrowAnyException();
    }

    private void assertStructureRejected(HealthActivitySyncRequest request, String message) {
        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(message);
    }

    private void assertAccepted(ActivityPayload activity) {
        assertStructureValid(request(TODAY, activity));
        assertThat(validator.rejectionReason(TODAY, activity)).isEmpty();
    }

    private void assertReason(ActivityPayload activity, String reason) {
        assertStructureValid(request(TODAY, activity)); // 구조 오류가 아니므로 요청 전체는 거부되지 않는다.
        assertThat(validator.rejectionReason(TODAY, activity)).contains(reason);
    }

    private static ActivityPayload workoutWithCalories(int minutes, String calories) {
        return workout(WorkoutType.RUNNING, minutes, new BigDecimal(calories), null, minutesBeforeNow(minutes), NOW);
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
}
