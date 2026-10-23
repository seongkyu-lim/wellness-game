package io.github.seongkyulim.wellnessgame.data.health

import io.github.seongkyulim.wellnessgame.domain.WorkoutKind
import java.time.Clock
import java.time.Duration
import java.time.LocalDate

/** 에뮬레이터·디버그 확인용 고정 데이터(iOS `MockHealthDataProvider`와 같은 값). */
class MockHealthDataProvider(private val clock: Clock = Clock.systemDefaultZone()) : HealthDataProvider {
    override val requiredPermissions: Set<String> = emptySet()

    override suspend fun availability() = HealthAvailability.AVAILABLE

    override suspend fun hasPermissions() = true

    override suspend fun loadToday(): DailyHealthSnapshot {
        val today = LocalDate.now(clock)
        val dayStart = HealthActivityMapper.startOfDay(today, clock.zone)
        val workoutStart = dayStart.plus(Duration.ofHours(7))
        val workoutEnd = workoutStart.plus(Duration.ofMinutes(45))
        val sleepStart = dayStart.minus(Duration.ofHours(2))
        val sleepEnd = dayStart.plus(Duration.ofHours(6))
        return DailyHealthSnapshot(
            date = today,
            steps = 8_500,
            workouts = listOf(
                HealthActivityMapper.workoutActivity(
                    kind = WorkoutKind.SWIMMING,
                    activeDuration = Duration.ofMinutes(45),
                    calories = 420.0,
                    distanceMeters = 1_200.0,
                    startedAt = workoutStart,
                    endedAt = workoutEnd,
                ),
            ),
            sleep = HealthActivityMapper.sleepActivity(listOf(TimeInterval(sleepStart, sleepEnd))),
        )
    }
}
