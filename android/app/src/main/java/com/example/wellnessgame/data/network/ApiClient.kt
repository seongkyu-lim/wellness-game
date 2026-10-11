package com.example.wellnessgame.data.network

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import retrofit2.Response
import java.io.IOException

/** 보호 API에 실을 토큰의 출처. 세션 관리자가 구현한다. */
interface AccessTokenSource {
    /** 지금 로그인한 세션의 액세스 토큰. 로그인하지 않았으면 null. */
    fun currentToken(): String?

    /** 서버가 [rejectedToken]을 거절(401)했다. 현재 토큰과 같을 때만 세션을 지워야 한다. */
    fun onUnauthorized(rejectedToken: String)
}

/**
 * [WellnessApi] 호출을 감싸 상태 코드를 [ApiException]으로 바꾸고, 401이면 토큰을 무효화한다.
 * 모든 요청의 `Accept-Language`는 [AcceptLanguageInterceptor]가 붙인다.
 */
class ApiClient(
    private val api: WellnessApi,
    private val tokens: AccessTokenSource,
    private val json: Json = ApiJson,
) {
    suspend fun signUp(username: String, password: String): AuthTokenResponse =
        call { api.signUp(SignUpRequest(username, password)) }

    suspend fun logIn(username: String, password: String): AuthTokenResponse =
        call { api.logIn(LoginRequest(username, password)) }

    suspend fun signInWithGoogle(idToken: String): AuthTokenResponse =
        call { api.google(GoogleNativeLoginRequest(idToken)) }

    suspend fun signInWithKakao(accessToken: String): AuthTokenResponse =
        call { api.kakao(SocialAccessTokenRequest(accessToken)) }

    suspend fun signInWithNaver(accessToken: String, refreshToken: String): AuthTokenResponse =
        call { api.naver(NaverNativeLoginRequest(accessToken, refreshToken)) }

    suspend fun sync(request: HealthActivitySyncRequest): HealthActivitySyncResponse =
        authorized { api.sync(it, request) }

    /** 캐릭터가 아직 없으면(첫 동기화 전, 404) null. */
    suspend fun myCharacter(): CharacterSummaryResponse? = try {
        authorized { api.myCharacter(it) }
    } catch (e: ApiException.Server) {
        if (e.status == 404) null else throw e
    }

    suspend fun dailyActivities(date: String): DailyActivitiesResponse =
        authorized { api.dailyActivities(it, date) }

    private suspend fun <T> authorized(block: suspend (authorization: String) -> Response<T>): T {
        val token = tokens.currentToken() ?: throw ApiException.Unauthorized(null)
        return try {
            call { block("Bearer $token") }
        } catch (e: ApiException.Unauthorized) {
            tokens.onUnauthorized(token)
            throw e
        }
    }

    private suspend fun <T> call(block: suspend () -> Response<T>): T {
        val response = try {
            block()
        } catch (e: IOException) {
            throw ApiException.Network(e)
        } catch (e: SerializationException) {
            throw ApiException.InvalidResponse(e)
        }
        if (response.isSuccessful) {
            return response.body() ?: throw ApiException.InvalidResponse()
        }
        val message = serverMessage(response)
        throw when (response.code()) {
            401 -> ApiException.Unauthorized(message)
            429 -> ApiException.RateLimited(parseRetryAfter(response.headers()["Retry-After"]), message)
            else -> ApiException.Server(response.code(), message)
        }
    }

    private fun serverMessage(response: Response<*>): String? {
        val raw = try {
            response.errorBody()?.string()
        } catch (_: IOException) {
            null
        }
        if (raw.isNullOrBlank()) return null
        return try {
            json.decodeFromString(ServerErrorBody.serializer(), raw).message?.takeIf { it.isNotBlank() }
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    companion object {
        /** `Retry-After`의 초 값. 서버는 정수 초만 보낸다. 해석하지 못하면 null. */
        fun parseRetryAfter(value: String?): Long? = value?.trim()?.toLongOrNull()?.takeIf { it >= 0 }
    }
}
