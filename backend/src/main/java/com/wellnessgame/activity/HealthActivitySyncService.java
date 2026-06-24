package com.wellnessgame.activity;

import com.wellnessgame.api.HealthActivitySyncRequest;
import com.wellnessgame.api.HealthActivitySyncRequest.ActivityPayload;
import com.wellnessgame.api.HealthActivitySyncResponse;
import com.wellnessgame.api.HealthActivitySyncResponse.ActivityResultResponse;
import com.wellnessgame.api.HealthActivitySyncResponse.CharacterResponse;
import com.wellnessgame.api.HealthActivitySyncResponse.StatsResponse;
import com.wellnessgame.character.CharacterStats;
import com.wellnessgame.character.UserCharacter;
import com.wellnessgame.character.UserCharacterRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class HealthActivitySyncService {
    private final UserCharacterRepository characterRepository;
    private final HealthActivityRepository activityRepository;
    private final ActivityXpCalculator xpCalculator;

    public HealthActivitySyncService(
            UserCharacterRepository characterRepository,
            HealthActivityRepository activityRepository,
            ActivityXpCalculator xpCalculator
    ) {
        this.characterRepository = characterRepository;
        this.activityRepository = activityRepository;
        this.xpCalculator = xpCalculator;
    }

    @Transactional
    public HealthActivitySyncResponse sync(HealthActivitySyncRequest request) {
        UserCharacter character = characterRepository.findByUserId(request.userId())
                .orElseGet(() -> new UserCharacter(request.userId()));

        int gainedXp = 0;
        boolean levelUp = false;
        List<ActivityResultResponse> results = new ArrayList<>();
        Set<String> requestKeys = new HashSet<>();

        for (ActivityPayload activity : request.activities()) {
            validatePayload(activity);
            String externalKey = externalKey(request, activity);
            boolean duplicate = !requestKeys.add(externalKey) || activityRepository.existsByExternalKey(externalKey);
            String source = source(activity);

            if (duplicate) {
                results.add(new ActivityResultResponse(
                        activity.type(),
                        source,
                        0,
                        source + " 이미 반영됨",
                        true
                ));
                continue;
            }

            int activityXp = xpCalculator.calculate(activity);
            applyStats(character.getStats(), activity);
            levelUp = character.addXp(activityXp) || levelUp;
            gainedXp += activityXp;

            activityRepository.save(toEntity(request, activity, activityXp, externalKey, source));
            results.add(new ActivityResultResponse(
                    activity.type(),
                    source,
                    activityXp,
                    rewardMessage(activity, source, activityXp),
                    false
            ));
        }

        UserCharacter saved = characterRepository.save(character);
        CharacterStats stats = saved.getStats();
        return new HealthActivitySyncResponse(
                request.userId(),
                request.date(),
                gainedXp,
                levelUp,
                new CharacterResponse(
                        saved.getLevel(),
                        saved.getCurrentXp(),
                        saved.getTotalXp(),
                        saved.nextLevelXp(),
                        new StatsResponse(
                                stats.getStr(),
                                stats.getVit(),
                                stats.getIntStat(),
                                stats.getDiscipline(),
                                stats.getRecovery()
                        )
                ),
                results
        );
    }

    private void validatePayload(ActivityPayload activity) {
        switch (activity.type()) {
            case STEPS -> require(activity.steps() != null, "STEPS 활동에는 steps가 필요합니다.");
            case WORKOUT -> {
                require(activity.startedAt() != null && activity.endedAt() != null,
                        "WORKOUT 활동에는 startedAt과 endedAt이 필요합니다.");
                requireValidTimeRange(activity);
            }
            case SLEEP -> {
                require(activity.sleepMinutes() != null && activity.startedAt() != null && activity.endedAt() != null,
                        "SLEEP 활동에는 sleepMinutes, startedAt, endedAt이 필요합니다.");
                requireValidTimeRange(activity);
            }
        }
    }

    private void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    private void requireValidTimeRange(ActivityPayload activity) {
        require(!activity.endedAt().isBefore(activity.startedAt()),
                "활동 종료 시간은 시작 시간 이후여야 합니다.");
    }

    private String externalKey(HealthActivitySyncRequest request, ActivityPayload activity) {
        return switch (activity.type()) {
            case STEPS -> String.join("|", request.userId(), request.date().toString(), "STEPS");
            case WORKOUT -> String.join("|",
                    request.userId(),
                    activity.startedAt().toString(),
                    activity.endedAt().toString(),
                    source(activity));
            case SLEEP -> String.join("|",
                    request.userId(),
                    activity.startedAt().toString(),
                    activity.endedAt().toString(),
                    "SLEEP");
        };
    }

    private String source(ActivityPayload activity) {
        return activity.type() == ActivityType.WORKOUT
                ? (activity.workoutType() == null ? WorkoutType.OTHER : activity.workoutType()).name()
                : activity.type().name();
    }

    private void applyStats(CharacterStats stats, ActivityPayload activity) {
        switch (activity.type()) {
            case STEPS -> stats.addDiscipline(1);
            case WORKOUT -> {
                stats.addStrength(1);
                stats.addVitality(1);
                if (activity.workoutType() == WorkoutType.SWIMMING) {
                    stats.addVitality(2);
                }
            }
            case SLEEP -> {
                stats.addRecovery(1);
                if (activity.sleepMinutes() != null && activity.sleepMinutes() >= 420) {
                    stats.addVitality(1);
                }
            }
        }
    }

    private HealthActivity toEntity(
            HealthActivitySyncRequest request,
            ActivityPayload activity,
            int xp,
            String externalKey,
            String source
    ) {
        return new HealthActivity(
                request.userId(),
                request.date(),
                activity.type(),
                source,
                activity.durationMinutes(),
                orZero(activity.calories()),
                orZero(activity.distanceMeters()),
                activity.steps(),
                activity.sleepMinutes(),
                activity.sleepScore(),
                xp,
                externalKey,
                activity.startedAt(),
                activity.endedAt()
        );
    }

    private BigDecimal orZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private String rewardMessage(ActivityPayload activity, String source, int xp) {
        return switch (activity.type()) {
            case STEPS -> "걸음 수 보상 +" + xp + " XP";
            case WORKOUT -> workoutName(source) + " 완료 +" + xp + " XP";
            case SLEEP -> "수면 회복 보너스 +" + xp + " XP";
        };
    }

    private String workoutName(String source) {
        return switch (source) {
            case "SWIMMING" -> "수영";
            case "RUNNING" -> "달리기";
            case "WALKING" -> "걷기";
            case "CYCLING" -> "자전거";
            case "STRENGTH_TRAINING" -> "근력 운동";
            default -> "운동";
        };
    }
}
