package io.github.seongkyulim.wellnessgame.domain

import androidx.health.connect.client.records.ExerciseSessionRecord

/**
 * 서버 `WorkoutType`과 1:1로 대응하는 운동 종류. iOS `WorkoutKind`와 같은 값·매핑을 쓴다.
 * 근력 운동은 일반 근력·웨이트 모두 STRENGTH_TRAINING으로 묶는다(iOS의 전통·기능성 근력과 같은 취지).
 */
enum class WorkoutKind(val serverType: String) {
    RUNNING("RUNNING"),
    WALKING("WALKING"),
    CYCLING("CYCLING"),
    SWIMMING("SWIMMING"),
    STRENGTH_TRAINING("STRENGTH_TRAINING"),
    OTHER("OTHER");

    companion object {
        /** Health Connect `ExerciseSessionRecord.EXERCISE_TYPE_*` → 서버 종류. */
        fun fromHealthConnect(exerciseType: Int): WorkoutKind = when (exerciseType) {
            ExerciseSessionRecord.EXERCISE_TYPE_RUNNING,
            ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL -> RUNNING
            ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> WALKING
            ExerciseSessionRecord.EXERCISE_TYPE_BIKING,
            ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY -> CYCLING
            ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL,
            ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER -> SWIMMING
            ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING,
            ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING -> STRENGTH_TRAINING
            else -> OTHER
        }

        fun fromServer(value: String?): WorkoutKind? = entries.firstOrNull { it.serverType == value }
    }
}
