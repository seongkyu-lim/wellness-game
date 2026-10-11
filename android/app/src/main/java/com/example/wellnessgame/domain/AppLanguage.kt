package com.example.wellnessgame.domain

import java.util.Locale

/** 서버와 주고받는 언어 코드. 서버는 ko·en만 번역하므로 그 둘로 좁힌다(iOS `AppLanguage`). */
object AppLanguage {
    val SUPPORTED = setOf("ko", "en")
    const val FALLBACK = "en"

    /** 선호 언어 순서대로 처음 만나는 ko/en. 둘 다 없으면 en(앱 기본 리소스와 같은 언어). */
    fun acceptLanguage(preferredLanguageTags: List<String>): String =
        preferredLanguageTags.firstNotNullOfOrNull(::normalized) ?: FALLBACK

    /** `ko-KR`, `en_US` 같은 식별자를 지원 언어 코드로. 지원하지 않으면 null. */
    fun normalized(tag: String?): String? {
        if (tag.isNullOrBlank()) return null
        val code = Locale.forLanguageTag(tag.replace('_', '-')).language.lowercase(Locale.ROOT)
        return code.takeIf { it in SUPPORTED }
    }
}
