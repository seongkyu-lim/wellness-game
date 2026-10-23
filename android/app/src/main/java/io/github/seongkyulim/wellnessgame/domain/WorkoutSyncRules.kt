package io.github.seongkyulim.wellnessgame.domain

import java.time.Duration
import java.time.Instant
import kotlin.math.floor

/**
 * 운동 기록을 서버 검증 규칙(SPEC.md §5)에 맞춰 보내기 위한 계산. iOS `WorkoutSyncRules`를 그대로 옮겼다.
 * - 분 값: 서버는 `분 ≤ 구간(분, 내림) + 1`을 허용하므로 반올림한 활동 시간은 항상 통과한다.
 * - 칼로리: 서버는 `칼로리 × 60 ≤ 20 × 구간(초)`를 넘으면 해당 운동을 건너뛴다. 넘는 몫은 잘라서 보낸다.
 */
object WorkoutSyncRules {
    /** 서버 `HealthActivitySyncValidator.MAX_CALORIES_PER_MINUTE`. */
    const val MAX_CALORIES_PER_MINUTE = 20.0
    /** 서버 `MINUTE_ROUNDING_TOLERANCE`. */
    const val MINUTE_ROUNDING_TOLERANCE = 1
    /** 시각은 초 단위 ISO-8601로 보내므로 시작·종료가 각각 잘리면 구간이 최대 1초 줄어든다. */
    private const val ENCODING_SLACK_SECONDS = 1.0

    /** 서버에 보내는 운동 분 값. 활동 시간을 반올림하고 최소 1분으로 둔다. */
    fun durationMinutes(activeDuration: Duration): Int {
        val seconds = maxOf(0.0, activeDuration.toMillis() / 1000.0)
        return maxOf(1, (seconds / 60).roundHalfUp().toInt())
    }

    /** 서버가 받아들이는 분 값의 상한: 구간(분, 내림) + 1. */
    fun maxAcceptedMinutes(start: Instant, end: Instant): Int =
        (intervalSeconds(start, end) / 60).toInt() + MINUTE_ROUNDING_TOLERANCE

    /** 구간 길이에서 허용되는 최대 칼로리. 시각 인코딩 오차만큼 구간을 짧게 잡아 보수적으로 계산한다. */
    fun calorieCap(start: Instant, end: Instant): Double {
        val seconds = maxOf(0.0, floor(intervalSeconds(start, end)) - ENCODING_SLACK_SECONDS)
        return MAX_CALORIES_PER_MINUTE * seconds / 60
    }

    /** 칼로리를 서버 상한 안으로 자른다(0.1kcal 단위 내림). 값이 없으면 null 그대로. */
    fun clampedCalories(calories: Double?, start: Instant, end: Instant): Double? {
        if (calories == null) return null
        val clamped = minOf(maxOf(0.0, calories), calorieCap(start, end))
        return floor(clamped * 10) / 10
    }

    private fun intervalSeconds(start: Instant, end: Instant): Double =
        maxOf(0.0, Duration.between(start, end).toMillis() / 1000.0)

    /** Swift `rounded()`(.toNearestOrAwayFromZero)와 같은 반올림. 음수는 들어오지 않는다. */
    private fun Double.roundHalfUp(): Double = floor(this + 0.5)
}
