package com.wellnessgame.api;

import com.wellnessgame.activity.ActivityType;
import com.wellnessgame.activity.HealthActivity;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record DailyActivitiesResponse(
        String userId,
        LocalDate date,
        List<ActivityEntry> activities
) {
    public record ActivityEntry(
            ActivityType type,
            String source,
            Integer durationMinutes,
            BigDecimal calories,
            BigDecimal distanceMeters,
            Integer steps,
            Integer sleepMinutes,
            Integer sleepScore,
            int gainedXp,
            Instant startedAt,
            Instant endedAt
    ) {
        public static ActivityEntry from(HealthActivity activity) {
            return new ActivityEntry(
                    activity.getType(),
                    activity.getSourceType(),
                    activity.getDurationMinutes(),
                    activity.getCalories(),
                    activity.getDistanceMeters(),
                    activity.getSteps(),
                    activity.getSleepMinutes(),
                    activity.getSleepScore(),
                    activity.getGainedXp(),
                    activity.getStartedAt(),
                    activity.getEndedAt()
            );
        }
    }
}
