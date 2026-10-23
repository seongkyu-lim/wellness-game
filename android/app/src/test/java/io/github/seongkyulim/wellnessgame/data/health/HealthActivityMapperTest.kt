package io.github.seongkyulim.wellnessgame.data.health

import io.github.seongkyulim.wellnessgame.data.network.ApiJson
import io.github.seongkyulim.wellnessgame.data.network.HealthActivityPayload
import io.github.seongkyulim.wellnessgame.data.network.HealthActivitySyncRequest
import io.github.seongkyulim.wellnessgame.domain.WorkoutKind
import io.github.seongkyulim.wellnessgame.domain.WorkoutSyncRules
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class HealthActivityMapperTest {
    private val t0 = Instant.parse("2026-10-10T14:00:00Z")

    private fun at(minutes: Long) = t0.plus(Duration.ofMinutes(minutes))

    // MARK: 걸음

    @Test
    fun steps_areNeverNegative() {
        assertEquals(HealthActivityPayload(type = "STEPS", steps = 8_500), HealthActivityMapper.stepsActivity(8_500))
        assertEquals(0, HealthActivityMapper.stepsActivity(-1).steps)
    }

    // MARK: 운동

    @Test
    fun workout_mapsKindMinutesCaloriesAndSecondPrecisionTimes() {
        val start = Instant.parse("2026-10-11T07:00:00.700Z")
        val end = Instant.parse("2026-10-11T07:45:00.200Z")
        val payload = HealthActivityMapper.workoutActivity(
            kind = WorkoutKind.SWIMMING,
            activeDuration = Duration.ofMinutes(45),
            calories = 420.0,
            distanceMeters = 1_200.0,
            startedAt = start,
            endedAt = end,
        )
        assertEquals("WORKOUT", payload.type)
        assertEquals("SWIMMING", payload.workoutType)
        assertEquals(45, payload.durationMinutes)
        assertEquals(420.0, payload.calories!!, 1e-9)
        assertEquals(1_200.0, payload.distanceMeters!!, 1e-9)
        assertEquals("2026-10-11T07:00:00Z", payload.startedAt)
        assertEquals("2026-10-11T07:45:00Z", payload.endedAt)
    }

    @Test
    fun workout_pausedTimeIsExcluded_andStaysWithinServerTolerance() {
        val start = at(0)
        val end = at(40)
        val payload = HealthActivityMapper.workoutActivity(WorkoutKind.RUNNING, Duration.ofMinutes(30), null, null, start, end)
        assertEquals(30, payload.durationMinutes)
        assertNull(payload.calories)
        assertTrue(payload.durationMinutes!! <= WorkoutSyncRules.maxAcceptedMinutes(start, end))
    }

    @Test
    fun workout_excessiveCalories_areClampedToServerCap() {
        val start = at(0)
        val end = at(30)
        val payload = HealthActivityMapper.workoutActivity(WorkoutKind.CYCLING, Duration.ofMinutes(30), 5_000.0, null, start, end)
        assertEquals(599.6, payload.calories!!, 1e-9)
        assertTrue(payload.calories!! * 60 <= 20.0 * (30 * 60 - 1))
    }

    // MARK: 수면

    @Test
    fun sleep_mergesOverlappingIntervals_andUsesFirstStartLastEnd() {
        val payload = HealthActivityMapper.sleepActivity(
            listOf(
                TimeInterval(at(60), at(180)),
                TimeInterval(at(0), at(90)), // 겹침 → 0~180
                TimeInterval(at(200), at(500)), // 20분 깨어 있음
            ),
        )!!
        assertEquals("SLEEP", payload.type)
        assertEquals(180 + 300, payload.sleepMinutes)
        assertEquals(HealthActivityMapper.format(at(0)), payload.startedAt)
        assertEquals(HealthActivityMapper.format(at(500)), payload.endedAt)
        assertEquals(90, payload.sleepScore)
    }

    @Test
    fun sleep_touchingIntervalsMerge_andMinutesNeverExceedSpanPlusOne() {
        val intervals = listOf(TimeInterval(at(0), at(100)), TimeInterval(at(100), at(200)))
        val merged = HealthActivityMapper.mergeIntervals(intervals)
        assertEquals(listOf(TimeInterval(at(0), at(200))), merged)
        val payload = HealthActivityMapper.sleepActivity(intervals)!!
        // 서버 규칙: 수면 시간 ≤ 구간 길이 + 1분, 최대 960분
        assertTrue(payload.sleepMinutes!! <= 200 + 1)
    }

    @Test
    fun sleep_secondsRoundToNearestMinute() {
        val start = t0
        val payload = HealthActivityMapper.sleepActivity(listOf(TimeInterval(start, start.plusSeconds(420 * 60 + 29))))!!
        assertEquals(420, payload.sleepMinutes)
        val roundedUp = HealthActivityMapper.sleepActivity(listOf(TimeInterval(start, start.plusSeconds(420 * 60 + 30))))!!
        assertEquals(421, roundedUp.sleepMinutes)
    }

    @Test
    fun sleep_noIntervals_isNull() {
        assertNull(HealthActivityMapper.sleepActivity(emptyList()))
    }

    @Test
    fun asleepIntervals_useOnlyAsleepStages_orWholeSessionWithoutStages() {
        val staged = SleepSessionInput(
            start = at(0),
            end = at(480),
            stages = listOf(
                SleepStageInput(at(0), at(20), 1), // AWAKE
                SleepStageInput(at(20), at(200), 4), // LIGHT
                SleepStageInput(at(200), at(300), 5), // DEEP
                SleepStageInput(at(300), at(320), 7), // AWAKE_IN_BED
                SleepStageInput(at(320), at(400), 6), // REM
                SleepStageInput(at(400), at(420), 3), // OUT_OF_BED
                SleepStageInput(at(420), at(480), 2), // SLEEPING
            ),
        )
        val unstaged = SleepSessionInput(at(600), at(660))
        val intervals = HealthActivityMapper.asleepIntervals(listOf(staged, unstaged))
        assertEquals(
            listOf(
                TimeInterval(at(20), at(200)),
                TimeInterval(at(200), at(300)),
                TimeInterval(at(320), at(400)),
                TimeInterval(at(420), at(480)),
                TimeInterval(at(600), at(660)),
            ),
            intervals,
        )
        val payload = HealthActivityMapper.sleepActivity(intervals)!!
        assertEquals(180 + 100 + 80 + 60 + 60, payload.sleepMinutes)
        assertEquals(HealthActivityMapper.format(at(20)), payload.startedAt)
        assertEquals(HealthActivityMapper.format(at(660)), payload.endedAt)
    }

    @Test
    fun sleepScore_matchesIosBuckets() {
        assertEquals(30, HealthActivityMapper.sleepScore(0))
        assertEquals(30, HealthActivityMapper.sleepScore(299))
        assertEquals(50, HealthActivityMapper.sleepScore(300))
        assertEquals(70, HealthActivityMapper.sleepScore(360))
        assertEquals(90, HealthActivityMapper.sleepScore(420))
        assertEquals(90, HealthActivityMapper.sleepScore(540))
        assertEquals(70, HealthActivityMapper.sleepScore(541))
    }

    @Test
    fun sleepWindow_startsAtNoonTheDayBefore() {
        val zone = ZoneId.of("Asia/Seoul")
        assertEquals(
            Instant.parse("2026-10-10T03:00:00Z"), // 10일 12:00 KST
            HealthActivityMapper.sleepWindowStart(LocalDate.of(2026, 10, 11), zone),
        )
    }

    // MARK: 요청 JSON

    @Test
    fun snapshot_encodesLikeIosPayload_omittingNullFields() = runTest {
        val clock = Clock.fixed(Instant.parse("2026-10-11T03:00:00Z"), ZoneId.of("Asia/Seoul"))
        val snapshot = MockHealthDataProvider(clock).loadToday()
        val request = HealthActivitySyncRequest(userId = "u-1", date = snapshot.date.toString(), activities = snapshot.activities)
        val json = ApiJson.parseToJsonElement(ApiJson.encodeToString(HealthActivitySyncRequest.serializer(), request)).jsonObject

        assertEquals("u-1", json["userId"]!!.jsonPrimitive.content)
        assertEquals("2026-10-11", json["date"]!!.jsonPrimitive.content)
        val activities = json["activities"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("STEPS", "WORKOUT", "SLEEP"), activities.map { it["type"]!!.jsonPrimitive.content })

        val steps = activities[0]
        assertEquals(setOf("type", "steps"), steps.keys)
        assertEquals(8_500, steps["steps"]!!.jsonPrimitive.content.toInt())

        val workout = activities[1]
        assertEquals("SWIMMING", workout["workoutType"]!!.jsonPrimitive.content)
        assertEquals(45, workout["durationMinutes"]!!.jsonPrimitive.content.toInt())
        assertEquals("2026-10-10T22:00:00Z", workout["startedAt"]!!.jsonPrimitive.content) // 11일 07:00 KST
        assertFalse(workout.containsKey("sleepMinutes"))

        val sleep: JsonObject = activities[2]
        assertEquals(480, sleep["sleepMinutes"]!!.jsonPrimitive.content.toInt())
        assertEquals(90, sleep["sleepScore"]!!.jsonPrimitive.content.toInt())
        assertEquals("2026-10-10T13:00:00Z", sleep["startedAt"]!!.jsonPrimitive.content) // 10일 22:00 KST
        assertFalse(sleep.containsKey("workoutType"))
    }
}
