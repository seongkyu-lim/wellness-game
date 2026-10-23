package io.github.seongkyulim.wellnessgame.domain

import androidx.health.connect.client.records.ExerciseSessionRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class WorkoutKindTest {
    @Test
    fun serverTypes_matchServerEnum() {
        assertEquals(
            listOf("RUNNING", "WALKING", "CYCLING", "SWIMMING", "STRENGTH_TRAINING", "OTHER"),
            WorkoutKind.entries.map { it.serverType },
        )
    }

    @Test
    fun healthConnectTypes_mapLikeIos() {
        val expected = mapOf(
            ExerciseSessionRecord.EXERCISE_TYPE_RUNNING to WorkoutKind.RUNNING,
            ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL to WorkoutKind.RUNNING,
            ExerciseSessionRecord.EXERCISE_TYPE_WALKING to WorkoutKind.WALKING,
            ExerciseSessionRecord.EXERCISE_TYPE_BIKING to WorkoutKind.CYCLING,
            ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY to WorkoutKind.CYCLING,
            ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL to WorkoutKind.SWIMMING,
            ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER to WorkoutKind.SWIMMING,
            ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING to WorkoutKind.STRENGTH_TRAINING,
            ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING to WorkoutKind.STRENGTH_TRAINING,
            ExerciseSessionRecord.EXERCISE_TYPE_YOGA to WorkoutKind.OTHER,
            ExerciseSessionRecord.EXERCISE_TYPE_HIKING to WorkoutKind.OTHER,
            ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT to WorkoutKind.OTHER,
        )
        expected.forEach { (type, kind) -> assertEquals("type $type", kind, WorkoutKind.fromHealthConnect(type)) }
    }

    @Test
    fun fromServer_roundTrips() {
        WorkoutKind.entries.forEach { assertEquals(it, WorkoutKind.fromServer(it.serverType)) }
        assertNull(WorkoutKind.fromServer("YOGA"))
    }
}

class WorkoutSyncRulesTest {
    private val start = Instant.parse("2026-10-11T07:00:00Z")

    @Test
    fun durationMinutes_roundsAndKeepsAtLeastOneMinute() {
        assertEquals(1, WorkoutSyncRules.durationMinutes(Duration.ZERO))
        assertEquals(1, WorkoutSyncRules.durationMinutes(Duration.ofSeconds(29)))
        assertEquals(1, WorkoutSyncRules.durationMinutes(Duration.ofSeconds(89)))
        assertEquals(2, WorkoutSyncRules.durationMinutes(Duration.ofSeconds(90)))
        assertEquals(30, WorkoutSyncRules.durationMinutes(Duration.ofSeconds(30 * 60 + 29)))
        assertEquals(1, WorkoutSyncRules.durationMinutes(Duration.ofSeconds(-5)))
    }

    @Test
    fun roundedMinutes_neverExceedServerTolerance() {
        for (seconds in 1L..7_200L) {
            val end = start.plusSeconds(seconds)
            val minutes = WorkoutSyncRules.durationMinutes(Duration.ofSeconds(seconds))
            assertTrue("${seconds}s", minutes <= WorkoutSyncRules.maxAcceptedMinutes(start, end))
        }
    }

    @Test
    fun calories_withinCap_areKeptToOneDecimal() {
        val end = start.plus(Duration.ofMinutes(30))
        assertEquals(250.4, WorkoutSyncRules.clampedCalories(250.46, start, end)!!, 1e-9)
        assertNull(WorkoutSyncRules.clampedCalories(null, start, end))
        assertEquals(0.0, WorkoutSyncRules.clampedCalories(-3.0, start, end)!!, 1e-9)
    }

    @Test
    fun calories_aboveCap_areClampedBelowServerLimit() {
        for (seconds in listOf(60L, 61L, 600L, 1_799L, 1_800L, 3_601L)) {
            val end = start.plusSeconds(seconds)
            val clamped = WorkoutSyncRules.clampedCalories(100_000.0, start, end)!!
            // 서버는 초 단위로 잘린 시각을 받으므로 구간이 1초 줄어든 경우까지 통과해야 한다.
            val serverSeconds = (seconds - 1).toDouble()
            assertTrue("${seconds}s", clamped * 60 <= WorkoutSyncRules.MAX_CALORIES_PER_MINUTE * serverSeconds)
        }
        assertEquals(599.6, WorkoutSyncRules.clampedCalories(900.0, start, start.plus(Duration.ofMinutes(30)))!!, 1e-9)
    }
}
