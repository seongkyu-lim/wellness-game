package com.wellnessgame.api;

import com.wellnessgame.activity.ActivityType;
import com.wellnessgame.activity.WorkoutType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record HealthActivitySyncRequest(
        @NotBlank String userId,
        @NotNull LocalDate date,
        @NotEmpty
        @Size(max = 50, message = "한 번에 최대 50개의 활동까지 동기화할 수 있습니다.")
        List<@Valid ActivityPayload> activities
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

