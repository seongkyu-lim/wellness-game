package com.example.wellnessgame.data.network

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Query

/**
 * 서버 REST 계약. 보호 API는 `Authorization` 헤더를 인자로 받는다.
 * 요청에 실은 토큰을 호출부가 알아야 401 때 "그 토큰"만 지울 수 있기 때문이다(늦게 온 401이 새 세션을 지우지 않게).
 */
interface WellnessApi {
    @POST("api/auth/signup")
    suspend fun signUp(@Body body: SignUpRequest): Response<AuthTokenResponse>

    @POST("api/auth/login")
    suspend fun logIn(@Body body: LoginRequest): Response<AuthTokenResponse>

    @POST("api/auth/google/native")
    suspend fun google(@Body body: GoogleNativeLoginRequest): Response<AuthTokenResponse>

    @POST("api/auth/kakao/native")
    suspend fun kakao(@Body body: SocialAccessTokenRequest): Response<AuthTokenResponse>

    @POST("api/auth/naver/native")
    suspend fun naver(@Body body: NaverNativeLoginRequest): Response<AuthTokenResponse>

    @POST("api/auth/refresh")
    suspend fun refresh(@Body body: RefreshTokenRequest): Response<RefreshResponse>

    /** 무인증. Authorization을 싣지 않고 리프레시 토큰만 보낸다. 항상 204. */
    @POST("api/auth/logout")
    suspend fun logout(@Body body: RefreshTokenRequest): Response<Unit>

    @POST("api/health-activities/sync")
    suspend fun sync(
        @Header("Authorization") authorization: String,
        @Body body: HealthActivitySyncRequest,
    ): Response<HealthActivitySyncResponse>

    @GET("api/characters/me")
    suspend fun myCharacter(@Header("Authorization") authorization: String): Response<CharacterSummaryResponse>

    @GET("api/health-activities")
    suspend fun dailyActivities(
        @Header("Authorization") authorization: String,
        @Query("date") date: String,
    ): Response<DailyActivitiesResponse>
}
