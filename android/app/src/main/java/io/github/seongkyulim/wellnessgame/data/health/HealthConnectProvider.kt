package com.example.wellnessgame.data.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSegment
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.example.wellnessgame.domain.WorkoutKind
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import kotlin.reflect.KClass

/** Health Connect에서 오늘 데이터를 읽는다. 매핑 규칙은 [HealthActivityMapper]에 있다. */
class HealthConnectProvider(
    private val context: Context,
    private val clock: Clock = Clock.systemDefaultZone(),
) : HealthDataProvider {

    override val requiredPermissions: Set<String> = READ_PERMISSIONS

    private val client: HealthConnectClient by lazy { HealthConnectClient.getOrCreate(context) }

    override suspend fun availability(): HealthAvailability =
        when (HealthConnectClient.getSdkStatus(context)) {
            HealthConnectClient.SDK_AVAILABLE -> HealthAvailability.AVAILABLE
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> HealthAvailability.PROVIDER_UPDATE_REQUIRED
            else -> HealthAvailability.UNSUPPORTED
        }

    override suspend fun hasPermissions(): Boolean {
        if (availability() != HealthAvailability.AVAILABLE) return false
        return client.permissionController.getGrantedPermissions().containsAll(requiredPermissions)
    }

    /** 백그라운드(WorkManager) 읽기를 지원하면 요청할 권한까지 포함한 집합. */
    override suspend fun permissionsToRequest(): Set<String> {
        if (availability() != HealthAvailability.AVAILABLE) return requiredPermissions
        val background = client.features.getFeatureStatus(
            HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND,
        ) == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
        return if (background) requiredPermissions + HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND else requiredPermissions
    }

    override suspend fun loadToday(): DailyHealthSnapshot {
        if (availability() != HealthAvailability.AVAILABLE) {
            throw HealthDataException(HealthDataException.Reason.UNAVAILABLE)
        }
        if (!hasPermissions()) throw HealthDataException(HealthDataException.Reason.PERMISSION_DENIED)

        val now = clock.instant()
        val zone = clock.zone
        val today = LocalDate.now(clock)
        val dayStart = HealthActivityMapper.startOfDay(today, zone)
        return try {
            DailyHealthSnapshot(
                date = today,
                steps = readSteps(dayStart, now),
                workouts = readWorkouts(dayStart, now),
                sleep = readSleep(HealthActivityMapper.sleepWindowStart(today, zone), now),
            )
        } catch (e: SecurityException) {
            throw HealthDataException(HealthDataException.Reason.PERMISSION_DENIED, e)
        } catch (e: HealthDataException) {
            throw e
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            throw HealthDataException(HealthDataException.Reason.READ_FAILED, e)
        }
    }

    private suspend fun readSteps(start: Instant, end: Instant): Int {
        val result = client.aggregate(
            AggregateRequest(setOf(StepsRecord.COUNT_TOTAL), TimeRangeFilter.between(start, end)),
        )
        return (result[StepsRecord.COUNT_TOTAL] ?: 0L).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
    }

    /** 오늘 0시 이후 시작한 운동(iOS strictStartDate와 같음). */
    private suspend fun readWorkouts(dayStart: Instant, now: Instant) =
        readAll(ExerciseSessionRecord::class, TimeRangeFilter.between(dayStart, now))
            .filter { !it.startTime.isBefore(dayStart) && it.endTime.isAfter(it.startTime) }
            .sortedBy { it.startTime }
            .map { session ->
                val window = TimeRangeFilter.between(session.startTime, session.endTime)
                val totals = client.aggregate(
                    AggregateRequest(
                        setOf(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL, DistanceRecord.DISTANCE_TOTAL),
                        window,
                    ),
                )
                HealthActivityMapper.workoutActivity(
                    kind = WorkoutKind.fromHealthConnect(session.exerciseType),
                    activeDuration = activeDuration(session),
                    calories = totals[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories,
                    distanceMeters = totals[DistanceRecord.DISTANCE_TOTAL]?.inMeters,
                    startedAt = session.startTime,
                    endedAt = session.endTime,
                )
            }

    /** 세션 길이에서 일시정지·휴식 구간을 뺀 활동 시간. */
    private fun activeDuration(session: ExerciseSessionRecord): Duration {
        val paused = session.segments
            .filter {
                it.segmentType == ExerciseSegment.EXERCISE_SEGMENT_TYPE_PAUSE ||
                    it.segmentType == ExerciseSegment.EXERCISE_SEGMENT_TYPE_REST
            }
            .fold(Duration.ZERO) { acc, segment -> acc + Duration.between(segment.startTime, segment.endTime) }
        val total = Duration.between(session.startTime, session.endTime)
        return (total - paused).takeIf { !it.isNegative } ?: Duration.ZERO
    }

    private suspend fun readSleep(windowStart: Instant, now: Instant) =
        readAll(SleepSessionRecord::class, TimeRangeFilter.between(windowStart, now))
            .map { session ->
                SleepSessionInput(
                    start = session.startTime,
                    end = session.endTime,
                    stages = session.stages.map { SleepStageInput(it.startTime, it.endTime, it.stage) },
                )
            }
            .let { HealthActivityMapper.sleepActivity(HealthActivityMapper.asleepIntervals(it)) }

    private suspend fun <T : Record> readAll(type: KClass<T>, filter: TimeRangeFilter): List<T> {
        val records = mutableListOf<T>()
        var pageToken: String? = null
        do {
            val response = client.readRecords(ReadRecordsRequest(type, filter, pageToken = pageToken))
            records += response.records
            pageToken = response.pageToken
        } while (pageToken != null)
        return records
    }

    companion object {
        val READ_PERMISSIONS: Set<String> = setOf(
            HealthPermission.getReadPermission(StepsRecord::class),
            HealthPermission.getReadPermission(ExerciseSessionRecord::class),
            HealthPermission.getReadPermission(SleepSessionRecord::class),
            HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
            HealthPermission.getReadPermission(DistanceRecord::class),
        )

        const val PROVIDER_PACKAGE = "com.google.android.apps.healthdata"
    }
}
