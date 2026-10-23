package com.example.wellnessgame.domain

/** 로그인 수단. Apple은 Android에서 제공하지 않는다. */
enum class LoginProvider(val storageKey: String) {
    GOOGLE("google"),
    KAKAO("kakao"),
    NAVER("naver"),
    PASSWORD("password");

    companion object {
        fun fromStorageKey(value: String?): LoginProvider? = entries.firstOrNull { it.storageKey == value }
    }
}

/**
 * 지역에 따라 소셜 로그인 수단의 노출 순서를 정한다(iOS `LoginProviderPolicy`, Apple 제외). UX 분기일 뿐 서버는 모두 받는다.
 * - 한국(KR): 카카오, 네이버, 구글을 모두 우선 노출한다.
 * - 그 외·알 수 없음: 구글을 우선 노출하고 카카오, 네이버는 "다른 방법으로 로그인" 아래 접어 둔다.
 * - ID/비밀번호는 별도 폼으로 항상 제공하므로 이 목록에 넣지 않는다.
 */
data class LoginProviderPolicy(
    /** 처음부터 보이는 수단(표시 순서). */
    val primary: List<LoginProvider>,
    /** "다른 방법으로 로그인"을 눌러야 보이는 수단(표시 순서). */
    val secondary: List<LoginProvider>,
) {
    companion object {
        val SOCIAL_PROVIDERS = listOf(LoginProvider.KAKAO, LoginProvider.NAVER, LoginProvider.GOOGLE)
        private val OVERSEAS_PRIMARY = listOf(LoginProvider.GOOGLE)

        /** @param regionCode 기기 지역(ISO 3166-1 alpha-2). 모르면 null·빈 값. */
        fun forRegion(regionCode: String?): LoginProviderPolicy {
            val primary = if (regionCode.equals("KR", ignoreCase = true)) SOCIAL_PROVIDERS else OVERSEAS_PRIMARY
            return LoginProviderPolicy(primary, SOCIAL_PROVIDERS.filterNot { it in primary })
        }
    }
}
