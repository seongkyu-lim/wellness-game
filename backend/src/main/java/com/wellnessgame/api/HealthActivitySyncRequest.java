package com.wellnessgame.api;

import com.wellnessgame.activity.ActivityType;
import com.wellnessgame.activity.WorkoutType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record HealthActivitySyncRequest(
        @NotBlank String userId,
        @NotNull LocalDate date,
        @NotEmpty List<@Valid ActivityPayload> activities
) {
    public record ActivityPayload(
            @NotNull ActivityType type,
            WorkoutType workoutType,
            @PositiveOrZero Integer durationMinutes,
            @PositiveOrZero BigDecimal calories,
            @PositiveOrZero BigDecimal distanceMeters,
            @PositiveOrZero Integer steps,
            @PositiveOrZero Integer sleepMinutes,
            @PositiveOrZero Integer sleepScore,
            Instant startedAt,
            Instant endedAt
    ) {
    }
}

