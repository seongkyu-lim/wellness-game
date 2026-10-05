package com.wellnessgame.api;

import com.wellnessgame.activity.ActivityType;
import com.wellnessgame.activity.HealthActivitySyncValidator;
import com.wellnessgame.activity.WorkoutType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * @param userId 선택. 없으면 토큰 주체를 쓰고, 있으면 토큰 주체와 같아야 한다(다르면 403).
 */
public record HealthActivitySyncRequest(
        String userId,
        @NotNull LocalDate date,
        @NotEmpty
        @Size(max = HealthActivitySyncValidator.MAX_ACTIVITIES_PER_REQUEST,
                message = "한 번에 최대 {max}개의 활동까지 동기화할 수 있습니다.")
        List<@Valid ActivityPayload> activities
) {
    /** 토큰 주체로 확정된 userId 를 담은 사본. */
    public HealthActivitySyncRequest withUserId(String resolvedUserId) {
        return new HealthActivitySyncRequest(resolvedUserId, date, activities);
    }

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

