package com.wellnessgame.api;

import com.wellnessgame.activity.ActivityType;

import java.time.LocalDate;
import java.util.List;

public record HealthActivitySyncResponse(
        String userId,
        LocalDate date,
        int gainedXp,
        boolean levelUp,
        CharacterResponse character,
        List<ActivityResultResponse> activityResults
) {
    public record CharacterResponse(
            int level,
            int currentXp,
            int totalXp,
            int nextLevelXp,
            StatsResponse stats
    ) {
    }

    public record StatsResponse(
            int str,
            int vit,
            int intStat,
            int discipline,
            int recovery
    ) {
    }

    public record ActivityResultResponse(
            ActivityType type,
            String source,
            int gainedXp,
            String message,
            boolean duplicate
    ) {
    }
}

