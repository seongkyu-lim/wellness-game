package com.example.wellnessgame.data.network

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import retrofit2.Response
import java.io.IOException

/** `POST /api/auth/refresh` 호출. [ApiClient]가 넘기고 세션 관리자가 단일 비행으로 부른다. */
fun interface TokenRefresh {
    suspend fun refresh(refreshToken: String): RefreshResponse
}

/** 보호 API에 실을 토큰의 출처. 세션 관리자가 구현한다. */
interface AccessTokenSource {
    /**
     * 요청에 실을 액세스 토큰. 곧 만료되면 [refresh]로 미리 갱신한다. 로그인하지 않았거나 세션이 끝났으면 null.
     * 갱신이 일시적으로 실패해도 기존 토큰이 아직 유효하면 그것을 돌려준다.
     */
    suspend fun tokenForRequest(refresh: TokenRefresh): String?

    /**
     * 서버가 [rejectedToken]을 거절(401)했다. 다른 요청이 이미 갱신했으면 그 토큰을, 아니면 [refresh]로 갱신한 토큰을
     * 돌려준다. 갱신할 수 없으면(리프레시 없음·거절) 세션을 지우고 null. 일시적 실패는 [ApiException]으로 던진다.
     */
    suspend fun renewAfterUnauthorized(rejectedToken: String, refresh: TokenRefresh): String?

    /** 서버가 [rejectedToken]을 거절(401)했고 더 갱신하지 않는다. 현재 토큰과 같을 때만 세션을 지워야 한다. */
    suspend fun onUnauthorized(rejectedToken: String)
}

/**
 * [WellnessApi] 호출을 감싸 상태 코드를 [ApiException]으로 바꾼다.
 * 보호 API는 401이면 리프레시 토큰으로 한 번 갱신해 원 요청을 한 번만 다시 보내고, 그래도 401이면 세션을 끝낸다.
 * 모든 요청의 `Accept-Language`는 [AcceptLanguageInterceptor]가 붙인다.
 *
 * 갱신을 OkHttp `Authenticator` 대신 여기서 하는 이유: 보호 API는 `Authorization`을 호출 인자로 받아
 * "그 요청에 실은 토큰"을 호출부가 알아야 늦게 온 401이 새 세션을 지우지 않는다. 또 코루틴 [kotlinx.coroutines.sync.Mutex]로
 * 단일 비행을 묶어 OkHttp 스레드를 막지 않는다. 앱의 모든 보호 요청(백그라운드 [com.example.wellnessgame.sync.SyncWorker] 포함)이
 * [authorized]를 거친다.
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

    /**
     * 이 기기 로그아웃. 무인증 경로라 `Authorization` 없이 리프레시 토큰만 보낸다(액세스 토큰이 만료됐어도 폐기할 수 있게).
     * 서버는 항상 204다.
     */
    suspend fun logOut(refreshToken: String) {
        val response = try {
            api.logout(RefreshTokenRequest(refreshToken))
        } catch (e: IOException) {
            throw ApiException.Network(e)
        }
        if (!response.isSuccessful) throw ApiException.Server(response.code(), null)
    }

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

    private val refresher = TokenRefresh { refreshToken -> call { api.refresh(RefreshTokenRequest(refreshToken)) } }

    private suspend fun <T> authorized(block: suspend (authorization: String) -> Response<T>): T {
        val token = tokens.tokenForRequest(refresher) ?: throw ApiException.Unauthorized(null)
        try {
            return call { block("Bearer $token") }
        } catch (e: ApiException.Unauthorized) {
            val renewed = tokens.renewAfterUnauthorized(token, refresher) ?: throw e
            try {
                return call { block("Bearer $renewed") }
            } catch (retry: ApiException.Unauthorized) {
                tokens.onUnauthorized(renewed)
                throw retry
            }
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
