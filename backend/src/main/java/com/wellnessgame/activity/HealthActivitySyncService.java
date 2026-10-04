package com.wellnessgame.activity;

import com.wellnessgame.api.HealthActivitySyncRequest;
import com.wellnessgame.api.HealthActivitySyncRequest.ActivityPayload;
import com.wellnessgame.api.HealthActivitySyncResponse;
import com.wellnessgame.api.HealthActivitySyncResponse.ActivityResultResponse;
import com.wellnessgame.api.HealthActivitySyncResponse.CharacterResponse;
import com.wellnessgame.api.HealthActivitySyncResponse.GoalResponse;
import com.wellnessgame.character.CharacterStats;
import com.wellnessgame.character.UserCharacter;
import com.wellnessgame.character.UserCharacterRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
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

        request.activities().forEach(this::validatePayload);
        ActivityPayload effectiveSteps = maxStepsPayload(request.activities());

        int gainedXp = 0;
        boolean levelUp = false;
        List<ActivityResultResponse> results = new ArrayList<>();
        Set<String> requestKeys = new HashSet<>();

        for (ActivityPayload activity : request.activities()) {
            String source = source(activity);

            if (activity.type() == ActivityType.STEPS) {
                // 같은 요청 내 STEPS 는 최대값 하나만 반영하고 나머지는 중복 처리한다.
                int stepsXp = activity == effectiveSteps
                        ? upsertSteps(request, activity, character)
                        : 0;
                if (stepsXp > 0) {
                    levelUp = character.addXp(stepsXp) || levelUp;
                    gainedXp += stepsXp;
                    results.add(new ActivityResultResponse(
                            activity.type(), source, stepsXp, rewardMessage(activity, source, stepsXp), false));
                } else {
                    results.add(duplicateResult(activity, source));
                }
                continue;
            }

            String externalKey = externalKey(request, activity);
            boolean duplicate = !requestKeys.add(externalKey) || activityRepository.existsByExternalKey(externalKey);

            if (duplicate) {
                results.add(duplicateResult(activity, source));
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
        return new HealthActivitySyncResponse(
                request.userId(),
                request.date(),
                gainedXp,
                levelUp,
                CharacterResponse.from(saved),
                results,
                dailyGoals(request.userId(), request.date())
        );
    }

    /**
     * 요청 내 STEPS 중 걸음 수가 가장 큰 payload (동률이면 먼저 나온 것). 없으면 null.
     */
    private ActivityPayload maxStepsPayload(List<ActivityPayload> activities) {
        ActivityPayload max = null;
        for (ActivityPayload activity : activities) {
            if (activity.type() == ActivityType.STEPS
                    && (max == null || value(activity.steps()) > value(max.steps()))) {
                max = activity;
            }
        }
        return max;
    }

    /**
     * 그날의 STEPS 로그를 생성하거나 더 큰 걸음 수로 갱신하고, 새로 지급할 XP 차액을 반환한다.
     * 스탯(DISCIPLINE)도 여기서 반영한다. 변화가 없으면 0 을 반환한다.
     */
    private int upsertSteps(HealthActivitySyncRequest request, ActivityPayload activity, UserCharacter character) {
        String externalKey = externalKey(request, activity);
        int newSteps = Math.max(value(activity.steps()), 0);
        int newXp = xpCalculator.stepsXp(newSteps);
        CharacterStats stats = character.getStats();

        Optional<HealthActivity> existing = activityRepository.findByExternalKey(externalKey);
        if (existing.isEmpty()) {
            stats.addDiscipline(1); // 걸음 활동 기본 보상: 그날 첫 STEPS 로그 생성 시 1회
            if (newSteps >= ActivityXpCalculator.STEPS_GOAL) {
                stats.addDiscipline(1);
            }
            activityRepository.save(toEntity(request, activity, newXp, externalKey, source(activity)));
            return newXp;
        }

        HealthActivity log = existing.get();
        int previousSteps = value(log.getSteps());
        if (newSteps <= previousSteps) {
            return 0;
        }

        if (previousSteps < ActivityXpCalculator.STEPS_GOAL && newSteps >= ActivityXpCalculator.STEPS_GOAL) {
            stats.addDiscipline(1); // 목표 달성 보너스: 미달 → 달성 전환 시 1회
        }
        int xpDelta = Math.max(newXp - log.getGainedXp(), 0);
        log.updateSteps(newSteps, Math.max(newXp, log.getGainedXp()));
        return xpDelta;
    }

    private ActivityResultResponse duplicateResult(ActivityPayload activity, String source) {
        return new ActivityResultResponse(activity.type(), source, 0, source + " 이미 반영됨", true);
    }

    private List<GoalResponse> dailyGoals(String userId, LocalDate date) {
        int steps = 0;
        int workoutMinutes = 0;
        int sleepMinutes = 0;
        for (HealthActivity activity : activityRepository.findByUserIdAndActivityDate(userId, date)) {
            switch (activity.getType()) {
                case STEPS -> steps = Math.max(steps, value(activity.getSteps()));
                case WORKOUT -> workoutMinutes += value(activity.getDurationMinutes());
                case SLEEP -> sleepMinutes = Math.max(sleepMinutes, value(activity.getSleepMinutes()));
            }
        }
        return List.of(
                GoalResponse.of(ActivityType.STEPS, ActivityXpCalculator.STEPS_GOAL, steps, "steps"),
                GoalResponse.of(ActivityType.WORKOUT, ActivityXpCalculator.WORKOUT_GOAL_MINUTES, workoutMinutes, "minutes"),
                GoalResponse.of(ActivityType.SLEEP, ActivityXpCalculator.SLEEP_GOAL_MINUTES, sleepMinutes, "minutes")
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
            case STEPS -> {
                // STEPS 스탯은 upsertSteps 에서 생성/갱신 여부에 따라 반영한다.
            }
            case WORKOUT -> applyWorkoutStats(stats, activity.workoutType());
            case SLEEP -> {
                stats.addRecovery(1);
                int score = xpCalculator.resolvedSleepScore(value(activity.sleepMinutes()), activity.sleepScore());
                stats.addIntelligence(score >= 80 ? 2 : 1);
            }
        }
    }

    private void applyWorkoutStats(CharacterStats stats, WorkoutType workoutType) {
        WorkoutType type = workoutType == null ? WorkoutType.OTHER : workoutType;
        switch (type) {
            case STRENGTH_TRAINING -> {
                stats.addStrength(2);
                stats.addVitality(1);
            }
            case SWIMMING, RUNNING, CYCLING, WALKING -> {
                stats.addVitality(2);
                stats.addStrength(1);
            }
            case OTHER -> {
                stats.addStrength(1);
                stats.addVitality(1);
            }
        }
    }

    private int value(Integer value) {
        return value == null ? 0 : value;
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
