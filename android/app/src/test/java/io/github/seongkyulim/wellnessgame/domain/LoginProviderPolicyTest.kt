package com.example.wellnessgame.domain

import com.example.wellnessgame.domain.LoginProvider.GOOGLE
import com.example.wellnessgame.domain.LoginProvider.KAKAO
import com.example.wellnessgame.domain.LoginProvider.NAVER
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LoginProviderPolicyTest {
    @Test
    fun korea_showsKakaoNaverGoogle_withNothingFolded() {
        val policy = LoginProviderPolicy.forRegion("KR")
        assertEquals(listOf(KAKAO, NAVER, GOOGLE), policy.primary)
        assertEquals(emptyList<LoginProvider>(), policy.secondary)
    }

    @Test
    fun korea_regionCodeIsCaseInsensitive() {
        assertEquals(LoginProviderPolicy.forRegion("KR"), LoginProviderPolicy.forRegion("kr"))
    }

    @Test
    fun overseas_showsGoogleFirst_andFoldsKakaoNaver() {
        val policy = LoginProviderPolicy.forRegion("US")
        assertEquals(listOf(GOOGLE), policy.primary)
        assertEquals(listOf(KAKAO, NAVER), policy.secondary)
    }

    @Test
    fun unknownRegion_behavesLikeOverseas() {
        val overseas = LoginProviderPolicy.forRegion("US")
        assertEquals(overseas, LoginProviderPolicy.forRegion(null))
        assertEquals(overseas, LoginProviderPolicy.forRegion(""))
    }

    @Test
    fun everyRegion_offersEverySocialProviderExactlyOnce_andNeverPassword() {
        listOf("KR", "US", "JP", null).forEach { region ->
            val policy = LoginProviderPolicy.forRegion(region)
            val all = policy.primary + policy.secondary
            assertEquals(region.toString(), LoginProviderPolicy.SOCIAL_PROVIDERS.toSet(), all.toSet())
            assertEquals(region.toString(), all.size, all.toSet().size)
            assertFalse(LoginProvider.PASSWORD in all)
        }
    }
}
