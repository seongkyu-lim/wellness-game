package com.example.wellnessgame.data.network

import kotlinx.serialization.Serializable

// 서버 DTO(backend com.wellnessgame.api)·iOS(ios/Shared/Models, Networking)와 필드명이 1:1로 일치해야 한다.

@Serializable
data class SignUpRequest(val username: String, val password: String, val displayName: String? = null)

@Serializable
data class LoginRequest(val username: String, val password: String)

@Serializable
data class GoogleNativeLoginRequest(val idToken: String)

/** Kakao 네이티브 로그인 요청. SDK가 발급한 액세스 토큰만 보낸다. */
@Serializable
data class SocialAccessTokenRequest(val accessToken: String)

/** Naver 네이티브 로그인 요청. 서버가 토큰 대체 공격을 막을 수 있게 refresh token도 함께 보낸다. */
@Serializable
data class NaverNativeLoginRequest(val accessToken: String, val refreshToken: String)

/**
 * 로그인·회원가입·소셜 토큰 교환의 공통 응답.
 * Auth v2부터 [refreshToken]·[refreshExpiresIn](초)이 함께 온다. 이전 서버 응답에는 없을 수 있다.
 */
@Serializable
data class AuthTokenResponse(
    val userId: String,
    val displayName: String? = null,
    val accessToken: String,
    val tokenType: String? = null,
    val expiresIn: Long? = null,
    val refreshToken: String? = null,
    val refreshExpiresIn: Long? = null,
)

/** `POST /api/auth/refresh`·`POST /api/auth/logout` 요청. 무인증 경로라 리프레시 토큰 소유가 권한이다. */
@Serializable
data class RefreshTokenRequest(val refreshToken: String)

/** `POST /api/auth/refresh` 응답. 리프레시 토큰은 매번 회전되므로 반드시 온다. */
@Serializable
data class RefreshResponse(
    val userId: String,
    val accessToken: String,
    val tokenType: String? = null,
    val expiresIn: Long? = null,
    val refreshToken: String,
    val refreshExpiresIn: Long? = null,
)

/**
 * 동기화 활동 한 건. 값이 없는 필드는 보내지 않는다(iOS `HealthActivityDTO`와 같은 JSON).
 * 시각은 초 단위 ISO-8601 UTC(`2026-10-11T03:00:00Z`).
 */
@Serializable
data class HealthActivityPayload(
    val type: String,
    val workoutType: String? = null,
    val durationMinutes: Int? = null,
    val calories: Double? = null,
    val distanceMeters: Double? = null,
    val steps: Int? = null,
    val sleepMinutes: Int? = null,
    val sleepScore: Int? = null,
    val startedAt: String? = null,
    val endedAt: String? = null,
) {
    companion object {
        const val TYPE_STEPS = "STEPS"
        const val TYPE_WORKOUT = "WORKOUT"
        const val TYPE_SLEEP = "SLEEP"
    }
}

/** `POST /api/health-activities/sync`. date는 기기 로컬 날짜(yyyy-MM-dd). */
@Serializable
data class HealthActivitySyncRequest(
    val userId: String,
    val date: String,
    val activities: List<HealthActivityPayload>,
)

@Serializable
data class HealthActivitySyncResponse(
    val userId: String,
    val date: String,
    val gainedXp: Int,
    val levelUp: Boolean,
    val character: CharacterState,
    val activityResults: List<ActivityXpResult> = emptyList(),
)

@Serializable
data class CharacterState(
    val level: Int,
    val currentXp: Int,
    val totalXp: Int,
    val nextLevelXp: Int,
    val stats: CharacterStats,
)

@Serializable
data class CharacterStats(
    val str: Int,
    val vit: Int,
    val intStat: Int,
    val discipline: Int,
    val recovery: Int,
)

@Serializable
data class ActivityXpResult(
    val type: String,
    val source: String,
    val gainedXp: Int,
    val message: String,
    val duplicate: Boolean,
)

/** `GET /api/characters/me`. */
@Serializable
data class CharacterSummaryResponse(val userId: String, val character: CharacterState)

/** `GET /api/health-activities?date=yyyy-MM-dd`. 화면에 필요한 필드만 디코딩한다. */
@Serializable
data class DailyActivitiesResponse(
    val userId: String,
    val date: String,
    val activities: List<ActivityEntry> = emptyList(),
)

@Serializable
data class ActivityEntry(
    val type: String,
    val durationMinutes: Int? = null,
    val steps: Int? = null,
    val sleepMinutes: Int? = null,
    val gainedXp: Int = 0,
)

@Serializable
internal data class ServerErrorBody(val message: String? = null)
