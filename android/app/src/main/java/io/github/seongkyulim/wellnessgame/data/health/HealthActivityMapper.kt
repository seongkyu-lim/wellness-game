package io.github.seongkyulim.wellnessgame.data.health

import io.github.seongkyulim.wellnessgame.data.network.HealthActivityPayload
import io.github.seongkyulim.wellnessgame.domain.WorkoutKind
import io.github.seongkyulim.wellnessgame.domain.WorkoutSyncRules
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.floor

/** 시간 구간 [start, end). */
data class TimeInterval(val start: Instant, val end: Instant)

/** Health Connect 수면 세션을 SDK 타입 없이 다루기 위한 값. [stages]가 비어 있으면 세션 전체를 수면으로 본다. */
data class SleepSessionInput(val start: Instant, val end: Instant, val stages: List<SleepStageInput> = emptyList())

/** [type]은 Health Connect `SleepSessionRecord.STAGE_TYPE_*` 값. */
data class SleepStageInput(val start: Instant, val end: Instant, val type: Int)

/**
 * Health Connect 값 → 서버 동기화 항목. iOS `AppleHealthKitProvider`와 같은 규칙을 순수 함수로 둬 JVM에서 테스트한다.
 */
object HealthActivityMapper {
    // Health Connect SleepSessionRecord.STAGE_TYPE_* (SLEEPING·LIGHT·DEEP·REM = 잠든 상태)
    private const val STAGE_SLEEPING = 2
    private const val STAGE_LIGHT = 4
    private const val STAGE_DEEP = 5
    private const val STAGE_REM = 6
    private val ASLEEP_STAGES = setOf(STAGE_SLEEPING, STAGE_LIGHT, STAGE_DEEP, STAGE_REM)

    fun stepsActivity(steps: Int): HealthActivityPayload =
        HealthActivityPayload(type = HealthActivityPayload.TYPE_STEPS, steps = maxOf(0, steps))

    /**
     * 운동 항목. 분 값은 활동 시간(일시정지 제외) 반올림, 칼로리는 서버 분당 상한까지 자른다.
     * 서버는 상한을 넘는 운동을 통째로 건너뛰므로(0 XP) 이상값 때문에 운동 전체를 잃지 않게 한다(iOS와 같은 판단).
     */
    fun workoutActivity(
        kind: WorkoutKind,
        activeDuration: Duration,
        calories: Double?,
        distanceMeters: Double?,
        startedAt: Instant,
        endedAt: Instant,
    ): HealthActivityPayload = HealthActivityPayload(
        type = HealthActivityPayload.TYPE_WORKOUT,
        workoutType = kind.serverType,
        durationMinutes = WorkoutSyncRules.durationMinutes(activeDuration),
        calories = WorkoutSyncRules.clampedCalories(calories, startedAt, endedAt),
        distanceMeters = distanceMeters,
        startedAt = format(startedAt),
        endedAt = format(endedAt),
    )

    /** 세션별 잠든 구간. 단계가 있으면 잠든 단계만, 없으면 세션 전체. */
    fun asleepIntervals(sessions: List<SleepSessionInput>): List<TimeInterval> = sessions.flatMap { session ->
        if (session.stages.isEmpty()) {
            listOf(TimeInterval(session.start, session.end))
        } else {
            session.stages.filter { it.type in ASLEEP_STAGES }.map { TimeInterval(it.start, it.end) }
        }
    }.filter { it.end.isAfter(it.start) }

    /**
     * 수면 항목(하루 1건). 겹치는 구간을 합친 뒤 구간 합을 분으로 반올림하고, 시작은 첫 구간 시작·종료는 마지막 구간 끝.
     * 잠든 구간이 없으면 null.
     */
    fun sleepActivity(intervals: List<TimeInterval>): HealthActivityPayload? {
        val merged = mergeIntervals(intervals)
        if (merged.isEmpty()) return null
        val seconds = merged.sumOf { Duration.between(it.start, it.end).toMillis() / 1000.0 }
        val minutes = floor(seconds / 60 + 0.5).toInt()
        return HealthActivityPayload(
            type = HealthActivityPayload.TYPE_SLEEP,
            sleepMinutes = minutes,
            sleepScore = sleepScore(minutes),
            startedAt = format(merged.first().start),
            endedAt = format(merged.last().end),
        )
    }

    fun mergeIntervals(intervals: List<TimeInterval>): List<TimeInterval> =
        intervals.sortedBy { it.start }.fold(mutableListOf()) { merged, interval ->
            val last = merged.lastOrNull()
            if (last != null && !interval.start.isAfter(last.end)) {
                merged[merged.lastIndex] = TimeInterval(last.start, maxOf(last.end, interval.end))
            } else {
                merged.add(interval)
            }
            merged
        }

    /** iOS `sleepScore(minutes:)`와 같은 구간. */
    fun sleepScore(minutes: Int): Int = when {
        minutes in 420..540 -> 90
        minutes > 540 -> 70
        minutes in 360 until 420 -> 70
        minutes in 300 until 360 -> 50
        else -> 30
    }

    /** 오늘 로컬 0시. */
    fun startOfDay(date: LocalDate, zone: ZoneId): Instant = date.atStartOfDay(zone).toInstant()

    /** 수면 조회 창의 시작: 오늘 0시의 12시간 전(전날 12:00). */
    fun sleepWindowStart(date: LocalDate, zone: ZoneId): Instant =
        startOfDay(date, zone).minus(Duration.ofHours(12))

    /** 서버로 보내는 시각: 초 단위 ISO-8601 UTC. */
    fun format(instant: Instant): String = instant.truncatedTo(ChronoUnit.SECONDS).toString()
}
