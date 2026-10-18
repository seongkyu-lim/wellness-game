package com.wellnessgame.api;

import com.wellnessgame.activity.ActivityType;
import com.wellnessgame.character.CharacterStats;
import com.wellnessgame.character.UserCharacter;

import java.time.LocalDate;
import java.util.List;

public record HealthActivitySyncResponse(
        String userId,
        LocalDate date,
        int gainedXp,
        boolean levelUp,
        CharacterResponse character,
        List<ActivityResultResponse> activityResults,
        List<GoalResponse> goals
) {
    public record CharacterResponse(
            int level,
            int currentXp,
            int totalXp,
            int nextLevelXp,
            StatsResponse stats
    ) {
        public static CharacterResponse from(UserCharacter character) {
            CharacterStats stats = character.getStats();
            return new CharacterResponse(
                    character.getLevel(),
                    character.getCurrentXp(),
                    character.getTotalXp(),
                    character.nextLevelXp(),
                    new StatsResponse(
                            stats.getStr(),
                            stats.getVit(),
                            stats.getIntStat(),
                            stats.getDiscipline(),
                            stats.getRecovery()
                    )
            );
        }
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

    public record GoalResponse(
            ActivityType type,
            int target,
            int current,
            String unit,
            boolean achieved
    ) {
        public static GoalResponse of(ActivityType type, int target, int current, String unit) {
            return new GoalResponse(type, target, current, unit, current >= target);
        }
    }
}

