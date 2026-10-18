package com.example.wellnessgame.domain

import com.example.wellnessgame.data.network.ActivityEntry
import com.example.wellnessgame.data.network.ServerConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class GrowthStageTest {
    @Test
    fun levelThresholds_matchIosAndWeb() {
        assertEquals(GrowthStage.SEED, GrowthStage.forLevel(0))
        assertEquals(GrowthStage.SEED, GrowthStage.forLevel(2))
        assertEquals(GrowthStage.SPROUT, GrowthStage.forLevel(3))
        assertEquals(GrowthStage.SAPLING, GrowthStage.forLevel(6))
        assertEquals(GrowthStage.YOUNG, GrowthStage.forLevel(10))
        assertEquals(GrowthStage.TREE, GrowthStage.forLevel(15))
        assertEquals(GrowthStage.BLOSSOM, GrowthStage.forLevel(20))
        assertEquals(GrowthStage.BLOSSOM, GrowthStage.forLevel(99))
    }

    @Test
    fun nextStage() {
        assertEquals(GrowthStage.SPROUT, GrowthStage.nextAfter(1))
        assertEquals(GrowthStage.SPROUT, GrowthStage.nextAfter(-3))
        assertEquals(GrowthStage.BLOSSOM, GrowthStage.nextAfter(19))
        assertNull(GrowthStage.nextAfter(20))
    }
}

class ProgressAndQuestTest {
    @Test
    fun fraction_clampsAndHandlesZeroTotal() {
        assertEquals(0.5f, LevelProgress.fraction(50, 100))
        assertEquals(1f, LevelProgress.fraction(150, 100))
        assertEquals(0f, LevelProgress.fraction(-5, 100))
        assertEquals(0f, LevelProgress.fraction(5, 0))
    }

    @Test
    fun questsFromServerActivities_takeMaxStepsSleep_andSumWorkouts() {
        val quests = DailyQuestProgress.fromActivities(
            listOf(
                ActivityEntry(type = "STEPS", steps = 4_000),
                ActivityEntry(type = "STEPS", steps = 9_000),
                ActivityEntry(type = "WORKOUT", durationMinutes = 20),
                ActivityEntry(type = "WORKOUT", durationMinutes = 15),
                ActivityEntry(type = "SLEEP", sleepMinutes = 300),
                ActivityEntry(type = "SLEEP", sleepMinutes = 450),
            ),
        )
        assertEquals(DailyQuestProgress(steps = 9_000, workoutMinutes = 35, sleepMinutes = 450), quests)
        assertEquals(1f, quests.stepsProgress)
        assertEquals(1f, quests.workoutProgress)
        assertEquals(1f, quests.sleepProgress)
    }

    @Test
    fun questsFromEmptyActivities_areZero() {
        assertEquals(DailyQuestProgress(0, 0, 0), DailyQuestProgress.fromActivities(emptyList()))
    }
}

class AutoSyncPolicyTest {
    private val now = Instant.parse("2026-10-11T12:00:00Z")

    @Test
    fun syncsWhenNeverSynced_orAfterMinimumInterval() {
        assertTrue(AutoSyncPolicy.shouldSync(null, now))
        assertTrue(AutoSyncPolicy.shouldSync(now.minus(Duration.ofMinutes(6)), now))
        assertFalse(AutoSyncPolicy.shouldSync(now.minus(Duration.ofMinutes(4)), now))
        assertFalse(AutoSyncPolicy.shouldSync(now.minus(Duration.ofMinutes(5)), now))
    }

    @Test
    fun futureTimestamp_doesNotBlockSyncForever() {
        assertTrue(AutoSyncPolicy.shouldSync(now.plus(Duration.ofDays(1)), now))
    }
}

class AppLanguageTest {
    @Test
    fun picksFirstSupportedLanguage_inPreferenceOrder() {
        assertEquals("ko", AppLanguage.acceptLanguage(listOf("ko-KR", "en-US")))
        assertEquals("en", AppLanguage.acceptLanguage(listOf("en_GB", "ko")))
        assertEquals("ko", AppLanguage.acceptLanguage(listOf("ja-JP", "ko-KR")))
    }

    @Test
    fun fallsBackToEnglish() {
        assertEquals("en", AppLanguage.acceptLanguage(listOf("ja-JP", "fr")))
        assertEquals("en", AppLanguage.acceptLanguage(emptyList()))
        assertNull(AppLanguage.normalized(""))
    }
}

class ServerConfigTest {
    @Test
    fun debug_keepsConfiguredUrl_withTrailingSlash() {
        assertEquals("http://10.0.2.2:8080/", ServerConfig.resolveBaseUrl("http://10.0.2.2:8080", isDebug = true))
    }

    @Test
    fun debug_allowsCleartextForLocalBackend_andKeepsExistingSlash() {
        assertEquals("http://10.0.2.2:8080/", ServerConfig.resolveBaseUrl(" http://10.0.2.2:8080/ ", isDebug = true))
    }

    @Test
    fun release_isCaseInsensitiveAboutScheme_andRejectsOtherSchemes() {
        assertEquals("HTTPS://api.example.com/", ServerConfig.resolveBaseUrl("HTTPS://api.example.com/", isDebug = false))
        assertEquals(ServerConfig.RELEASE_FALLBACK, ServerConfig.resolveBaseUrl("", isDebug = false))
        assertEquals(ServerConfig.RELEASE_FALLBACK, ServerConfig.resolveBaseUrl("ftp://api.example.com", isDebug = false))
    }

    @Test
    fun release_acceptsHttpsOnly() {
        assertEquals("https://api.example.com/", ServerConfig.resolveBaseUrl("https://api.example.com", isDebug = false))
        assertEquals(ServerConfig.RELEASE_FALLBACK, ServerConfig.resolveBaseUrl("http://api.example.com", isDebug = false))
    }
}
