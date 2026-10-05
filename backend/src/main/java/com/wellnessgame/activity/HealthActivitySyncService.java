package com.wellnessgame.activity;

import com.wellnessgame.api.HealthActivitySyncRequest;
import com.wellnessgame.api.HealthActivitySyncRequest.ActivityPayload;
import com.wellnessgame.api.HealthActivitySyncResponse;
import com.wellnessgame.api.HealthActivitySyncResponse.ActivityResultResponse;
import com.wellnessgame.api.HealthActivitySyncResponse.CharacterResponse;
import com.wellnessgame.api.HealthActivitySyncResponse.GoalResponse;
import com.wellnessgame.character.CharacterStats;
import com.wellnessgame.character.UserCharacter;
import com.wellnessgame.character.UserCharacterProvisioner;
import com.wellnessgame.character.UserCharacterRepository;
import com.wellnessgame.i18n.LocalizedMessage;
import com.wellnessgame.i18n.Messages;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

@Service
public class HealthActivitySyncService {
    private final UserCharacterRepository characterRepository;
    private final UserCharacterProvisioner characterProvisioner;
    private final HealthActivityRepository activityRepository;
    private final ActivityXpCalculator xpCalculator;
    private final HealthActivitySyncValidator validator;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public HealthActivitySyncService(
            UserCharacterRepository characterRepository,
            UserCharacterProvisioner characterProvisioner,
            HealthActivityRepository activityRepository,
            ActivityXpCalculator xpCalculator,
            HealthActivitySyncValidator validator,
            Clock clock,
            PlatformTransactionManager transactionManager
    ) {
        this.characterRepository = characterRepository;
        this.characterProvisioner = characterProvisioner;
        this.activityRepository = activityRepository;
        this.xpCalculator = xpCalculator;
        this.validator = validator;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /**
     * 트랜잭션 밖에서 검증하고 신규 사용자 캐릭터를 먼저 만든 뒤(짧은 별도 트랜잭션), 나머지를 한 트랜잭션에서 처리한다.
     * 요청 하나가 동시에 DB 연결 두 개를 잡지 않도록 캐릭터 생성 트랜잭션을 동기화 트랜잭션 안에 중첩하지 않는다.
     */
    public HealthActivitySyncResponse sync(HealthActivitySyncRequest request) {
        validator.validate(request);
        characterProvisioner.createIfAbsent(request.userId());
        return transaction.execute(status -> syncInTransaction(request));
    }

    private HealthActivitySyncResponse syncInTransaction(HealthActivitySyncRequest request) {
        List<ActivityPayload> activities = request.activities().stream().map(this::withResolvedDuration).toList();

        // 캐릭터 행을 잠가 같은 사용자의 동시 동기화를 직렬화한 뒤 남은 일일 XP 를 계산한다.
        UserCharacter character = characterRepository.findByUserIdForUpdate(request.userId()).orElseThrow();
        SyncState state = new SyncState(request, character);
        // 이 요청이 로그를 쓰기 전에 관련 날짜의 남은 상한을 모두 읽어 둔다(이후 저장분이 합계에 이중 반영되지 않게).
        remainingDailyXp(state, request.date());
        for (ActivityPayload activity : activities) {
            if (activity.type() == ActivityType.WORKOUT) {
                remainingDailyXp(state, xpDate(request, activity));
                remainingWorkoutXp(state, xpDate(request, activity));
            }
        }

        List<LocalizedMessage> rejections = activities.stream()
                .map(activity -> validator.rejection(request.date(), activity).orElse(null))
                .toList();
        // 같은 요청 내 STEPS/SLEEP 은 (값 범위를 통과한 것 중) 최대값 하나만 반영한다.
        state.effectiveSteps = maxPayload(activities, rejections, ActivityType.STEPS, ActivityPayload::steps);
        state.effectiveSleep = maxPayload(activities, rejections, ActivityType.SLEEP, ActivityPayload::sleepMinutes);

        int gainedXp = 0;
        boolean levelUp = false;
        List<ActivityResultResponse> results = new ArrayList<>();

        for (int i = 0; i < activities.size(); i++) {
            ActivityPayload activity = activities.get(i);
            String source = source(activity);
            String externalKey = externalKey(request, activity);

            Decision decision = decide(state, activity, rejections.get(i), externalKey, source);
            if (decision.outcome() != Decision.Outcome.APPLIED) {
                results.add(new ActivityResultResponse(activity.type(), source, 0, skipMessage(source, decision), true));
                continue;
            }

            // 운동 XP 는 운동 시작 시각의 현지 날짜 기준으로 상한을 적용한다(요청 date 를 바꿔 나눠 보내도 우회 불가).
            LocalDate xpDate = xpDate(request, activity);
            int awardedXp = capToDaily(decision.earnedXp(), remainingDailyXp(state, xpDate));
            if (activity.type() == ActivityType.WORKOUT) {
                awardedXp = Math.min(awardedXp, remainingWorkoutXp(state, xpDate));
                state.remainingWorkoutXp.merge(xpDate, -awardedXp, Integer::sum);
            }
            state.remainingDailyXp.merge(xpDate, -awardedXp, Integer::sum);
            if (activity.type() == ActivityType.WORKOUT) {
                // STEPS/SLEEP 로그는 upsert 에서 같은 규칙(capToDaily)으로 실제 지급액을 기록한다.
                activityRepository.save(toEntity(request, xpDate, activity, awardedXp, externalKey, source));
            }
            levelUp = character.addXp(awardedXp) || levelUp;
            gainedXp += awardedXp;
            results.add(new ActivityResultResponse(
                    activity.type(),
                    source,
                    awardedXp,
                    rewardMessage(activity, source, awardedXp, awardedXp < decision.earnedXp()),
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
     * 한 요청을 처리하는 동안 유지하는 상태.
     */
    private static final class SyncState {
        private final HealthActivitySyncRequest request;
        private final UserCharacter character;
        private final Set<String> requestKeys = new HashSet<>();
        private final List<ActivityPayload> acceptedWorkouts = new ArrayList<>();
        /** 날짜별 남은 총 상한·운동 상한. 처음 쓸 때 DB 합계로 채운다. */
        private final Map<LocalDate, Integer> remainingDailyXp = new HashMap<>();
        private final Map<LocalDate, Integer> remainingWorkoutXp = new HashMap<>();
        private ActivityPayload effectiveSteps;
        private ActivityPayload effectiveSleep;

        private SyncState(HealthActivitySyncRequest request, UserCharacter character) {
            this.request = request;
            this.character = character;
        }
    }

    /**
     * 활동 하나의 처리 결과. APPLIED 일 때 earnedXp 는 일일 상한(총·유형별) 적용 전 XP 다.
     */
    private record Decision(Outcome outcome, int earnedXp, LocalizedMessage reason) {
        enum Outcome { APPLIED, DUPLICATE, OVERLAP, REJECTED }

        static final Decision DUPLICATE = new Decision(Outcome.DUPLICATE, 0, null);
        static final Decision OVERLAP = new Decision(Outcome.OVERLAP, 0, null);

        static Decision applied(int earnedXp) {
            return new Decision(Outcome.APPLIED, earnedXp, null);
        }

        static Decision rejected(LocalizedMessage reason) {
            return new Decision(Outcome.REJECTED, 0, reason);
        }

        /** upsert 결과(null 이면 변화 없음)를 판정으로 변환한다. */
        static Decision ofUpsert(Integer earnedXp) {
            return earnedXp == null ? DUPLICATE : applied(earnedXp);
        }
    }

    private Decision decide(SyncState state, ActivityPayload activity, LocalizedMessage rejection, String externalKey, String source) {
        if (rejection != null) {
            return Decision.rejected(rejection);
        }
        return switch (activity.type()) {
            case STEPS -> activity == state.effectiveSteps
                    ? Decision.ofUpsert(upsertSteps(state, activity, externalKey, source))
                    : Decision.DUPLICATE;
            case SLEEP -> activity == state.effectiveSleep
                    ? Decision.ofUpsert(upsertSleep(state, activity, externalKey, source))
                    : Decision.DUPLICATE;
            case WORKOUT -> decideWorkout(state, activity, externalKey);
        };
    }

    private Decision decideWorkout(SyncState state, ActivityPayload activity, String externalKey) {
        if (!state.requestKeys.add(externalKey) || activityRepository.existsByExternalKey(externalKey)) {
            return Decision.DUPLICATE;
        }
        // 다른 운동 기록과 시간 구간이 겹치면 이 항목만 건너뛴다(먼저 반영된 기록 우선).
        if (overlapsAccepted(state.acceptedWorkouts, activity) || activityRepository.existsOverlapping(
                state.request.userId(), ActivityType.WORKOUT, activity.startedAt(), activity.endedAt())) {
            return Decision.OVERLAP;
        }
        applyWorkoutStats(state.character.getStats(), activity.workoutType());
        state.acceptedWorkouts.add(activity);
        return Decision.applied(xpCalculator.calculate(activity));
    }

    private String skipMessage(String source, Decision decision) {
        return switch (decision.outcome()) {
            case OVERLAP -> Messages.get("activity.result.overlap", source);
            case REJECTED -> Messages.get("activity.result.rejected", source, Messages.get(decision.reason()));
            default -> Messages.get("activity.result.duplicate", source);
        };
    }

    private boolean overlapsAccepted(List<ActivityPayload> accepted, ActivityPayload activity) {
        return accepted.stream().anyMatch(other -> other.startedAt().isBefore(activity.endedAt())
                && activity.startedAt().isBefore(other.endedAt()));
    }

    /**
     * WORKOUT 의 durationMinutes 가 비어 있으면 시작~종료 구간 길이(분)로 채운다.
     */
    private ActivityPayload withResolvedDuration(ActivityPayload activity) {
        if (activity.type() != ActivityType.WORKOUT || activity.durationMinutes() != null) {
            return activity;
        }
        int spanMinutes = (int) Math.min(
                Duration.between(activity.startedAt(), activity.endedAt()).toMinutes(), Integer.MAX_VALUE);
        return new ActivityPayload(activity.type(), activity.workoutType(), spanMinutes, activity.calories(),
                activity.distanceMeters(), activity.steps(), activity.sleepMinutes(), activity.sleepScore(),
                activity.startedAt(), activity.endedAt());
    }

    /**
     * XP 상한을 적용할 날짜. WORKOUT 은 시작 시각의 서버 현지 날짜(로그 activityDate 도 같음), 나머지는 요청 date.
     */
    private LocalDate xpDate(HealthActivitySyncRequest request, ActivityPayload activity) {
        return activity.type() == ActivityType.WORKOUT
                ? LocalDate.ofInstant(activity.startedAt(), clock.getZone())
                : request.date();
    }

    private int remainingDailyXp(SyncState state, LocalDate date) {
        return state.remainingDailyXp.computeIfAbsent(date, d -> remainingUnder(ActivityXpCalculator.DAILY_XP_CAP,
                activityRepository.sumGainedXpByUserIdAndActivityDate(state.request.userId(), d)));
    }

    private int remainingWorkoutXp(SyncState state, LocalDate date) {
        return state.remainingWorkoutXp.computeIfAbsent(date, d -> remainingUnder(ActivityXpCalculator.DAILY_WORKOUT_XP_CAP,
                activityRepository.sumGainedXpByUserIdAndActivityDateAndType(state.request.userId(), d, ActivityType.WORKOUT)));
    }

    private static int remainingUnder(int cap, long alreadyGained) {
        return (int) Math.max(0, cap - alreadyGained);
    }

    /**
     * 일일 XP 상한을 적용한 실제 지급 XP.
     */
    private static int capToDaily(int earnedXp, int remainingDailyXp) {
        return Math.max(0, Math.min(earnedXp, remainingDailyXp));
    }

    /**
     * 값 범위를 통과한 해당 유형 payload 중 값이 가장 큰 것(동률이면 먼저 나온 것). 없으면 null.
     */
    private ActivityPayload maxPayload(
            List<ActivityPayload> activities,
            List<LocalizedMessage> rejections,
            ActivityType type,
            Function<ActivityPayload, Integer> valueOf
    ) {
        ActivityPayload max = null;
        for (int i = 0; i < activities.size(); i++) {
            ActivityPayload activity = activities.get(i);
            if (activity.type() == type && rejections.get(i) == null
                    && (max == null || value(valueOf.apply(activity)) > value(valueOf.apply(max)))) {
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
    private Integer upsertSteps(SyncState state, ActivityPayload activity, String externalKey, String source) {
        int newSteps = Math.max(value(activity.steps()), 0);
        int newXp = xpCalculator.stepsXp(newSteps);
        CharacterStats stats = state.character.getStats();

        Optional<HealthActivity> existing = activityRepository.findByExternalKey(externalKey);
        if (existing.isEmpty()) {
            stats.addDiscipline(1); // 걸음 활동 기본 보상: 그날 첫 STEPS 로그 생성 시 1회
            if (crossesStepsGoal(0, newSteps)) {
                stats.addDiscipline(1);
            }
            activityRepository.save(toEntity(state.request, state.request.date(), activity,
                    capToDaily(newXp, remainingDailyXp(state, state.request.date())), externalKey, source));
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
        log.updateSteps(newSteps, log.getGainedXp() + capToDaily(xpDelta, remainingDailyXp(state, state.request.date())));
        return xpDelta;
    }

    private boolean crossesStepsGoal(int previousSteps, int newSteps) {
        return previousSteps < ActivityXpCalculator.STEPS_GOAL && newSteps >= ActivityXpCalculator.STEPS_GOAL;
    }

    /**
     * 그날의 SLEEP 로그(날짜별 1건)를 생성하거나 더 긴 수면으로 갱신하고, 새로 지급할 XP 차액을 반환한다.
     * 스탯: 첫 로그 생성 시 RECOVERY +1, INT +1. 수면 점수가 품질 기준 미만 → 이상으로 처음 넘어갈 때 INT +1.
     * 수면 시간이 늘지 않았으면 null 을 반환한다. 이미 지급한 XP 는 회수하지 않는다.
     */
    private Integer upsertSleep(SyncState state, ActivityPayload activity, String externalKey, String source) {
        int newMinutes = value(activity.sleepMinutes());
        int newScore = xpCalculator.resolvedSleepScore(newMinutes, activity.sleepScore());
        int newXp = xpCalculator.sleepXp(newMinutes, activity.sleepScore());
        CharacterStats stats = state.character.getStats();

        Optional<HealthActivity> existing = activityRepository.findByExternalKey(externalKey);
        if (existing.isEmpty()) {
            stats.addRecovery(1);
            stats.addIntelligence(1);
            if (meetsSleepQuality(newScore)) {
                stats.addIntelligence(1);
            }
            activityRepository.save(toEntity(state.request, state.request.date(), activity,
                    capToDaily(newXp, remainingDailyXp(state, state.request.date())), externalKey, source));
            return newXp;
        }

        HealthActivity log = existing.get();
        int previousMinutes = value(log.getSleepMinutes());
        if (newMinutes <= previousMinutes) {
            return null;
        }

        int previousScore = xpCalculator.resolvedSleepScore(previousMinutes, log.getSleepScore());
        if (!meetsSleepQuality(previousScore) && meetsSleepQuality(newScore)) {
            stats.addIntelligence(1);
        }
        int xpDelta = Math.max(newXp - log.getGainedXp(), 0);
        log.updateSleep(newMinutes, activity.sleepScore(), activity.startedAt(), activity.endedAt(),
                log.getGainedXp() + capToDaily(xpDelta, remainingDailyXp(state, state.request.date())));
        return xpDelta;
    }

    private boolean meetsSleepQuality(int score) {
        return score >= ActivityXpCalculator.SLEEP_QUALITY_BONUS_SCORE;
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
            case SLEEP -> HealthActivity.sleepKey(request.userId(), request.date());
        };
    }

    private String source(ActivityPayload activity) {
        return activity.type() == ActivityType.WORKOUT
                ? (activity.workoutType() == null ? WorkoutType.OTHER : activity.workoutType()).name()
                : activity.type().name();
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
            return Integer.MAX_VALUE; // 분 값은 음수가 아니므로 양의 방향으로만 넘친다.
        }
    }

    private HealthActivity toEntity(
            HealthActivitySyncRequest request,
            LocalDate activityDate,
            ActivityPayload activity,
            int xp,
            String externalKey,
            String source
    ) {
        return new HealthActivity(
                request.userId(),
                activityDate,
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
            case STEPS -> Messages.get("activity.reward.steps", xp);
            // WORKOUT 의 source 는 WorkoutType 이름이다(source() 참고).
            case WORKOUT -> Messages.get("activity.reward.workout", Messages.get("workout.name." + source), xp);
            case SLEEP -> Messages.get("activity.reward.sleep", xp);
        };
        return capped ? Messages.get("activity.reward.capped", message) : message;
    }
}
