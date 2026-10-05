package com.wellnessgame.activity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "health_activity_log",
        uniqueConstraints = @UniqueConstraint(name = "uk_health_activity_external_key", columnNames = "external_key"),
        indexes = {
                @Index(name = "idx_health_activity_user_type_time", columnList = "user_id, type, started_at, ended_at"),
                @Index(name = "idx_health_activity_user_date", columnList = "user_id, activity_date")
        })
public class HealthActivity {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private String userId;

    @Column(name = "activity_date", nullable = false, updatable = false)
    private LocalDate activityDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private ActivityType type;

    @Column(name = "source_type")
    private String sourceType;

    @Column(name = "duration_minutes")
    private Integer durationMinutes;

    private BigDecimal calories;

    @Column(name = "distance_meters")
    private BigDecimal distanceMeters;

    private Integer steps;

    @Column(name = "sleep_minutes")
    private Integer sleepMinutes;

    @Column(name = "sleep_score")
    private Integer sleepScore;

    @Column(name = "gained_xp", nullable = false)
    private int gainedXp;

    @Column(name = "external_key", nullable = false, updatable = false, length = 512)
    private String externalKey;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected HealthActivity() {
    }

    public HealthActivity(
            String userId,
            LocalDate activityDate,
            ActivityType type,
            String sourceType,
            Integer durationMinutes,
            BigDecimal calories,
            BigDecimal distanceMeters,
            Integer steps,
            Integer sleepMinutes,
            Integer sleepScore,
            int gainedXp,
            String externalKey,
            Instant startedAt,
            Instant endedAt
    ) {
        this.userId = userId;
        this.activityDate = activityDate;
        this.type = type;
        this.sourceType = sourceType;
        this.durationMinutes = durationMinutes;
        this.calories = calories;
        this.distanceMeters = distanceMeters;
        this.steps = steps;
        this.sleepMinutes = sleepMinutes;
        this.sleepScore = sleepScore;
        this.gainedXp = gainedXp;
        this.externalKey = externalKey;
        this.startedAt = startedAt;
        this.endedAt = endedAt;
    }

    /** 날짜별 1건인 SLEEP 로그의 externalKey({@code userId|yyyy-MM-dd|SLEEP}). */
    public static String sleepKey(String userId, LocalDate activityDate) {
        return String.join("|", userId, activityDate.toString(), ActivityType.SLEEP.name());
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    /**
     * 같은 날 STEPS 재동기화 시 누적 걸음 수와 누적 지급 XP 를 갱신한다.
     */
    public void updateSteps(int steps, int gainedXp) {
        if (type != ActivityType.STEPS) {
            throw new IllegalStateException("STEPS 로그만 걸음 수를 갱신할 수 있습니다.");
        }
        this.steps = steps;
        this.gainedXp = gainedXp;
    }

    /**
     * 같은 날 SLEEP 재동기화 시 더 긴 수면 기록으로 갱신하고 누적 지급 XP 를 기록한다.
     */
    public void updateSleep(int sleepMinutes, Integer sleepScore, Instant startedAt, Instant endedAt, int gainedXp) {
        if (type != ActivityType.SLEEP) {
            throw new IllegalStateException("SLEEP 로그만 수면 기록을 갱신할 수 있습니다.");
        }
        this.sleepMinutes = sleepMinutes;
        this.sleepScore = sleepScore;
        this.startedAt = startedAt;
        this.endedAt = endedAt;
        this.gainedXp = gainedXp;
    }

    public UUID getId() {
        return id;
    }

    public String getUserId() {
        return userId;
    }

    public LocalDate getActivityDate() {
        return activityDate;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getExternalKey() {
        return externalKey;
    }

    public int getGainedXp() {
        return gainedXp;
    }
    public ActivityType getType() {
        return type;
    }

    public String getSourceType() {
        return sourceType;
    }

    public Integer getDurationMinutes() {
        return durationMinutes;
    }

    public BigDecimal getCalories() {
        return calories;
    }

    public BigDecimal getDistanceMeters() {
        return distanceMeters;
    }

    public Integer getSteps() {
        return steps;
    }

    public Integer getSleepMinutes() {
        return sleepMinutes;
    }

    public Integer getSleepScore() {
        return sleepScore;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getEndedAt() {
        return endedAt;
    }
}
