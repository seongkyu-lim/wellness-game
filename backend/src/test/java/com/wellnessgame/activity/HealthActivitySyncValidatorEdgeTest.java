package com.wellnessgame.activity;

import com.wellnessgame.api.HealthActivitySyncRequest;
import com.wellnessgame.api.HealthActivitySyncRequest.ActivityPayload;
import com.wellnessgame.error.BadRequestException;
import com.wellnessgame.i18n.LocalizedMessage;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static com.wellnessgame.support.ActivityPayloads.sleep;
import static com.wellnessgame.support.ActivityPayloads.steps;
import static com.wellnessgame.support.ActivityPayloads.workout;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 검증기의 필수 필드 누락·경계 시각·음수 값 같은 가장자리 사례. */
class HealthActivitySyncValidatorEdgeTest {
    private static final Instant NOW = Instant.parse("2026-06-17T12:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 6, 17);

    private final HealthActivitySyncValidator validator =
            new HealthActivitySyncValidator(Clock.fixed(NOW, ZoneOffset.UTC));

    private static HealthActivitySyncRequest request(LocalDate date, List<ActivityPayload> activities) {
        return new HealthActivitySyncRequest("u", date, activities);
    }

    @Test
    void nullOrEmptyActivitiesAreRejected() {
        assertThatThrownBy(() -> validator.validate(request(TODAY, null)))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> validator.validate(request(TODAY, List.of())))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void missingDateIsRejected() {
        assertThatThrownBy(() -> validator.validate(request(null, List.of(steps(1)))))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void activityWithoutTypeIsRejected() {
        ActivityPayload noType = new ActivityPayload(null, null, null, null, null, 1, null, null, null, null);
        assertThatThrownBy(() -> validator.validate(request(TODAY, List.of(noType))))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void stepsActivityWithoutStepsIsRejected() {
        ActivityPayload noSteps = new ActivityPayload(ActivityType.STEPS, null, null, null, null, null, null, null, null, null);
        assertThatThrownBy(() -> validator.validate(request(TODAY, List.of(noSteps))))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void sleepMissingAnyRequiredFieldIsRejected() {
        ActivityPayload noMinutes = new ActivityPayload(ActivityType.SLEEP, null, null, null, null, null, null, null,
                NOW.minusSeconds(3600), NOW);
        ActivityPayload noStart = new ActivityPayload(ActivityType.SLEEP, null, null, null, null, null, 60, null, null, NOW);
        ActivityPayload noEnd = new ActivityPayload(ActivityType.SLEEP, null, null, null, null, null, 60, null,
                NOW.minusSeconds(3600), null);
        for (ActivityPayload broken : List.of(noMinutes, noStart, noEnd)) {
            assertThatThrownBy(() -> validator.validate(request(TODAY, List.of(broken))))
                    .isInstanceOf(BadRequestException.class);
        }
    }

    @Test
    void workoutMissingEndOrStartIsRejected() {
        ActivityPayload noEnd = workout(WorkoutType.RUNNING, 30, NOW.minusSeconds(1800), null);
        assertThatThrownBy(() -> validator.validate(request(TODAY, List.of(noEnd))))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void futureToleranceBoundaryIsExactlyFiveMinutes() {
        ActivityPayload atLimit = workout(WorkoutType.RUNNING, 30, NOW.minusSeconds(1500), NOW.plusSeconds(300));
        ActivityPayload overLimit = workout(WorkoutType.RUNNING, 30, NOW.minusSeconds(1500), NOW.plusSeconds(301));

        assertThat(validator.rejection(TODAY, atLimit)).isEmpty();
        assertThat(validator.rejection(TODAY, overLimit).map(LocalizedMessage::code))
                .contains("sync.reason.future-time");
    }

    @Test
    void futureStartedAtIsRejectedEvenIfEndedAtIsNull() {
        ActivityPayload startFuture = new ActivityPayload(ActivityType.SLEEP, null, null, null, null, null, 60, null,
                NOW.plusSeconds(3600), null);
        assertThat(validator.rejection(TODAY, startFuture).map(LocalizedMessage::code))
                .contains("sync.reason.future-time");
    }

    @Test
    void dateWindowBoundariesFollowClockZone() {
        // 서울 시간대: 요청 날짜 6/17 의 창은 6/16 00:00 KST ~ 6/19 00:00 KST
        ZoneId seoul = ZoneId.of("Asia/Seoul");
        Clock seoulClock = Clock.fixed(Instant.parse("2026-06-17T03:00:00Z"), seoul);
        HealthActivitySyncValidator v = new HealthActivitySyncValidator(seoulClock);
        Instant windowStart = LocalDate.of(2026, 6, 16).atStartOfDay(seoul).toInstant();

        ActivityPayload atStart = workout(WorkoutType.RUNNING, 30, windowStart, windowStart.plusSeconds(1800));
        ActivityPayload beforeStart = workout(WorkoutType.RUNNING, 30,
                windowStart.minusSeconds(1), windowStart.plusSeconds(1800));

        assertThat(v.rejection(TODAY, atStart)).isEmpty();
        assertThat(v.rejection(TODAY, beforeStart).map(LocalizedMessage::code))
                .contains("sync.reason.outside-date-window");
    }

    @Test
    void negativeCaloriesAndDistanceAreOutOfRange() {
        ActivityPayload negCalories = workout(WorkoutType.RUNNING, 30, new BigDecimal("-1"), null,
                NOW.minusSeconds(1800), NOW);
        ActivityPayload negDistance = workout(WorkoutType.RUNNING, 30, null, new BigDecimal("-0.1"),
                NOW.minusSeconds(1800), NOW);

        assertThat(validator.rejection(TODAY, negCalories).map(LocalizedMessage::code))
                .contains("sync.reason.calories-range");
        assertThat(validator.rejection(TODAY, negDistance).map(LocalizedMessage::code))
                .contains("sync.reason.distance-range");
    }

    @Test
    void zeroCaloriesAndDistanceAreAccepted() {
        ActivityPayload zero = workout(WorkoutType.RUNNING, 30, BigDecimal.ZERO, BigDecimal.ZERO,
                NOW.minusSeconds(1800), NOW);
        assertThat(validator.rejection(TODAY, zero)).isEmpty();
    }

    @Test
    void sleepScoreIsOptional() {
        ActivityPayload noScore = sleep(450, null, NOW.minusSeconds(450 * 60L), NOW);
        assertThat(validator.rejection(TODAY, noScore)).isEmpty();
    }

    @Test
    void manyActivitiesAreAllStructurallyChecked() {
        List<ActivityPayload> list = new ArrayList<>();
        for (int i = 0; i < 49; i++) {
            list.add(steps(100));
        }
        list.add(new ActivityPayload(ActivityType.STEPS, null, null, null, null, null, null, null, null, null));
        assertThatThrownBy(() -> validator.validate(request(TODAY, list))).isInstanceOf(BadRequestException.class);
    }
}
