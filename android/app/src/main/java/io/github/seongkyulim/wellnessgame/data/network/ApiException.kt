package io.github.seongkyulim.wellnessgame.data.network

/** 서버 호출 실패. 화면은 종류별로 문구를 고른다. */
sealed class ApiException(message: String?, cause: Throwable? = null) : Exception(message, cause) {
    /** 토큰이 없거나 무효(HTTP 401). 세션을 정리하고 재로그인을 안내한다. */
    class Unauthorized(val serverMessage: String?) : ApiException(serverMessage)

    /** 요청 빈도 제한(HTTP 429). [retryAfterSeconds]는 `Retry-After` 헤더(초), 없으면 null. */
    class RateLimited(val retryAfterSeconds: Long?, val serverMessage: String?) : ApiException(serverMessage)

    /** 그 밖의 4xx/5xx. [serverMessage]는 서버가 번역해 보낸 문구(없을 수 있음). */
    class Server(val status: Int, val serverMessage: String?) : ApiException(serverMessage)

    /** 연결 실패·타임아웃. */
    class Network(cause: Throwable) : ApiException(cause.message, cause)

    /** 응답을 해석하지 못함. */
    class InvalidResponse(cause: Throwable? = null) : ApiException(cause?.message, cause)
}
