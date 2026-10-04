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
    private final HealthActivitySyncValidator validator;

    public HealthActivitySyncService(
            UserCharacterRepository characterRepository,
            HealthActivityRepository activityRepository,
            ActivityXpCalculator xpCalculator,
            HealthActivitySyncValidator validator
    ) {
        this.characterRepository = characterRepository;
        this.activityRepository = activityRepository;
        this.xpCalculator = xpCalculator;
        this.validator = validator;
    }

    @Transactional
    public HealthActivitySyncResponse sync(HealthActivitySyncRequest request) {
        validator.validate(request);

        UserCharacter character = characterRepository.findByUserId(request.userId())
                .orElseGet(() -> new UserCharacter(request.userId()));

        ActivityPayload effectiveSteps = maxStepsPayload(request.activities());
        int remainingDailyXp = remainingDailyXp(request.userId(), request.date());

        int gainedXp = 0;
        boolean levelUp = false;
        List<ActivityResultResponse> results = new ArrayList<>();
        Set<String> requestKeys = new HashSet<>();
        List<ActivityPayload> acceptedIntervals = new ArrayList<>();

        for (ActivityPayload activity : request.activities()) {
            String source = source(activity);
            String externalKey = externalKey(request, activity);

            // 반영할 XP(일일 상한 적용 전). null 이면 반영하지 않는 활동이며 skipMessage 로 사유를 남긴다.
            Integer activityXp = null;
            String skipMessage = source + " 이미 반영됨";
            if (activity.type() == ActivityType.STEPS) {
                // 같은 요청 내 STEPS 는 최대값 하나만 반영하고 나머지는 중복 처리한다.
                activityXp = activity == effectiveSteps
                        ? upsertSteps(request, activity, character, externalKey, source, remainingDailyXp)
                        : null;
            } else if (!requestKeys.add(externalKey) || activityRepository.existsByExternalKey(externalKey)) {
                // 동일 활동 재전송: 중복
            } else if (overlapsAccepted(acceptedIntervals, activity) || overlapsStored(request, activity)) {
                // 같은 유형의 다른 기록과 시간 구간이 겹치면 이 항목만 건너뛴다(먼저 반영된 기록 우선).
                skipMessage = source + " 겹치는 기록이 이미 반영됨";
            } else {
                activityXp = xpCalculator.calculate(activity);
                applyStats(character.getStats(), activity);
                acceptedIntervals.add(activity);
            }

            if (activityXp == null) {
                results.add(new ActivityResultResponse(activity.type(), source, 0, skipMessage, true));
                continue;
            }
            int awardedXp = capToDaily(activityXp, remainingDailyXp);
            remainingDailyXp -= awardedXp;
            if (activity.type() != ActivityType.STEPS) {
                // STEPS 로그는 upsertSteps 에서 같은 규칙(capToDaily)으로 실제 지급액을 기록한다.
                activityRepository.save(toEntity(request, activity, awardedXp, externalKey, source));
            }
            levelUp = character.addXp(awardedXp) || levelUp;
            gainedXp += awardedXp;
            results.add(new ActivityResultResponse(
                    activity.type(),
                    source,
                    awardedXp,
                    rewardMessage(activity, source, awardedXp, awardedXp < activityXp),
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

    private boolean overlapsAccepted(List<ActivityPayload> accepted, ActivityPayload activity) {
        return accepted.stream().anyMatch(other -> other.type() == activity.type()
                && other.startedAt().isBefore(activity.endedAt())
                && activity.startedAt().isBefore(other.endedAt()));
    }

    private boolean overlapsStored(HealthActivitySyncRequest request, ActivityPayload activity) {
        return activityRepository.existsOverlapping(
                request.userId(), activity.type(), activity.startedAt(), activity.endedAt());
    }

    private int remainingDailyXp(String userId, LocalDate date) {
        long alreadyGained = activityRepository.sumGainedXpByUserIdAndActivityDate(userId, date);
        return (int) Math.max(0, ActivityXpCalculator.DAILY_XP_CAP - alreadyGained);
    }

    /**
     * 일일 XP 상한을 적용한 실제 지급 XP.
     */
    private static int capToDaily(int earnedXp, int remainingDailyXp) {
        return Math.max(0, Math.min(earnedXp, remainingDailyXp));
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
     * 스탯(DISCIPLINE)도 여기서 반영한다. 걸음 수가 늘지 않았으면 null 을 반환한다.
     * 반환값은 일일 상한 적용 전 차액이며, 로그 gainedXp 에는 상한 적용 후 실제 지급 누적액을 기록한다.
     */
    private Integer upsertSteps(
            HealthActivitySyncRequest request,
            ActivityPayload activity,
            UserCharacter character,
            String externalKey,
            String source,
            int remainingDailyXp
    ) {
        int newSteps = Math.max(value(activity.steps()), 0);
        int newXp = xpCalculator.stepsXp(newSteps);
        CharacterStats stats = character.getStats();

        Optional<HealthActivity> existing = activityRepository.findByExternalKey(externalKey);
        if (existing.isEmpty()) {
            stats.addDiscipline(1); // 걸음 활동 기본 보상: 그날 첫 STEPS 로그 생성 시 1회
            if (crossesStepsGoal(0, newSteps)) {
                stats.addDiscipline(1);
            }
            activityRepository.save(toEntity(request, activity, capToDaily(newXp, remainingDailyXp), externalKey, source));
            return newXp;
        }

        HealthActivity log = existing.get();
        int previousSteps = value(log.getSteps());
        if (newSteps <= previousSteps) {
            return null;
        }

        if (crossesStepsGoal(previousSteps, newSteps)) {
            stats.addDiscipline(1); // 목표 달성 보너스: 미달 → 달성 전환 시 1회
        }
        // 같은 1,000보 구간이거나 상한에 도달했으면 차액은 0 이다. 이미 지급한 XP 는 회수하지 않는다.
        int xpDelta = Math.max(newXp - log.getGainedXp(), 0);
        log.updateSteps(newSteps, log.getGainedXp() + capToDaily(xpDelta, remainingDailyXp));
        return xpDelta;
    }

    private boolean crossesStepsGoal(int previousSteps, int newSteps) {
        return previousSteps < ActivityXpCalculator.STEPS_GOAL && newSteps >= ActivityXpCalculator.STEPS_GOAL;
    }

    private List<GoalResponse> dailyGoals(String userId, LocalDate date) {
        int steps = 0;
        int workoutMinutes = 0;
        int sleepMinutes = 0;
        for (HealthActivity activity : activityRepository.findByUserIdAndActivityDate(userId, date)) {
            switch (activity.getType()) {
                case STEPS -> steps = Math.max(steps, value(activity.getSteps()));
                case WORKOUT -> workoutMinutes = saturatedAdd(workoutMinutes, value(activity.getDurationMinutes()));
                case SLEEP -> sleepMinutes = Math.max(sleepMinutes, value(activity.getSleepMinutes()));
            }
        }
        return List.of(
                GoalResponse.of(ActivityType.STEPS, ActivityXpCalculator.STEPS_GOAL, steps, "steps"),
                GoalResponse.of(ActivityType.WORKOUT, ActivityXpCalculator.WORKOUT_GOAL_MINUTES, workoutMinutes, "minutes"),
                GoalResponse.of(ActivityType.SLEEP, ActivityXpCalculator.SLEEP_GOAL_MINUTES, sleepMinutes, "minutes")
        );
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

    private static int saturatedAdd(int a, int b) {
        try {
            return Math.addExact(a, b);
        } catch (ArithmeticException overflow) {
            return b > 0 ? Integer.MAX_VALUE : Integer.MIN_VALUE;
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

    private String rewardMessage(ActivityPayload activity, String source, int xp, boolean capped) {
        String message = switch (activity.type()) {
            case STEPS -> "걸음 수 보상 +" + xp + " XP";
            case WORKOUT -> workoutName(source) + " 완료 +" + xp + " XP";
            case SLEEP -> "수면 회복 보너스 +" + xp + " XP";
        };
        return capped ? message + " (일일 XP 상한 도달)" : message;
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
