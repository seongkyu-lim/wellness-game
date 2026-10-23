package io.github.seongkyulim.wellnessgame.domain

/** 진행률 계산. XP 바와 오늘의 퀘스트가 같은 규칙을 쓴다(iOS `LevelProgress`). */
object LevelProgress {
    /** `value / total`을 0..1로 자른 값. total이 0 이하이면 0. */
    fun fraction(value: Int, total: Int): Float {
        if (total <= 0) return 0f
        return (value.toFloat() / total).coerceIn(0f, 1f)
    }
}

/**
 * 오늘의 퀘스트 3종(걸음·운동·수면)의 현재값.
 * 목표값은 서버 `ActivityXpCalculator`의 STEPS_GOAL·WORKOUT_GOAL_MINUTES·SLEEP_GOAL_MINUTES와 같아야 한다.
 */
data class DailyQuestProgress(
    val steps: Int,
    val workoutMinutes: Int,
    val sleepMinutes: Int,
) {
    val stepsProgress: Float get() = LevelProgress.fraction(steps, STEPS_GOAL)
    val workoutProgress: Float get() = LevelProgress.fraction(workoutMinutes, WORKOUT_GOAL_MINUTES)
    val sleepProgress: Float get() = LevelProgress.fraction(sleepMinutes, SLEEP_GOAL_MINUTES)

    companion object {
        const val STEPS_GOAL = 8_000
        const val WORKOUT_GOAL_MINUTES = 30
        const val SLEEP_GOAL_MINUTES = 420
    }
}
