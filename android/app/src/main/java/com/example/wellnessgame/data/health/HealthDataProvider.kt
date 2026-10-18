package com.example.wellnessgame.data.health

import com.example.wellnessgame.data.network.HealthActivityPayload
import java.time.LocalDate

/** 건강 데이터 출처(Health Connect 또는 Mock). */
interface HealthDataProvider {
    suspend fun availability(): HealthAvailability

    /** 읽기에 필요한 권한(Health Connect 권한 문자열). Mock은 빈 집합. */
    val requiredPermissions: Set<String>

    suspend fun hasPermissions(): Boolean

    /** 권한 요청 화면에 넘길 권한. 기본은 [requiredPermissions]. */
    suspend fun permissionsToRequest(): Set<String> = requiredPermissions

    /** 오늘 걸음 합계, 오늘(로컬 0시 이후 시작) 운동, 전날 12:00~지금 수면을 읽는다. */
    suspend fun loadToday(): DailyHealthSnapshot
}

enum class HealthAvailability {
    AVAILABLE,

    /** Health Connect 앱이 없거나 업데이트가 필요하다(Android 13 이하). */
    PROVIDER_UPDATE_REQUIRED,

    /** 이 기기에서는 Health Connect를 쓸 수 없다. */
    UNSUPPORTED,
}

class HealthDataException(val reason: Reason, cause: Throwable? = null) : Exception(reason.name, cause) {
    enum class Reason { UNAVAILABLE, PERMISSION_DENIED, READ_FAILED }
}

/** 하루치 건강 데이터. [activities]는 iOS `DailyHealthSnapshot.activities`와 같은 순서(걸음 → 운동 → 수면)다. */
data class DailyHealthSnapshot(
    val date: LocalDate,
    val steps: Int,
    val workouts: List<HealthActivityPayload>,
    val sleep: HealthActivityPayload?,
) {
    val activities: List<HealthActivityPayload>
        get() = buildList {
            add(HealthActivityMapper.stepsActivity(steps))
            addAll(workouts)
            sleep?.let(::add)
        }

    val workoutMinutes: Int get() = workouts.sumOf { it.durationMinutes ?: 0 }
    val sleepMinutes: Int get() = sleep?.sleepMinutes ?: 0
}
