package io.github.seongkyulim.wellnessgame.data.network

/**
 * 서버 주소 결정. debug는 에뮬레이터의 호스트 localhost(`http://10.0.2.2:8080`),
 * release는 https만 쓴다. release에 http 주소가 들어오면 쓰지 않고 기본 https 주소로 대체한다
 * (gradle 설정 단계에서도 막지만, 잘못된 값이 들어와도 평문 통신으로 새지 않도록 한 번 더 막는다).
 */
object ServerConfig {
    const val RELEASE_FALLBACK = "https://api.wellnessgame.example/"

    fun resolveBaseUrl(configured: String, isDebug: Boolean): String {
        val normalized = configured.trim().let { if (it.endsWith("/")) it else "$it/" }
        if (isDebug) return normalized
        return if (normalized.startsWith("https://", ignoreCase = true)) normalized else RELEASE_FALLBACK
    }
}
