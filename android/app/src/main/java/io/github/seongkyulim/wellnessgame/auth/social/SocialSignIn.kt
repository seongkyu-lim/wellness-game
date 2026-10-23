package com.example.wellnessgame.auth.social

import android.app.Activity
import com.example.wellnessgame.domain.LoginProvider

/** 소셜 SDK가 발급한, 서버에 교환할 자격 증명. */
sealed interface SocialCredential {
    data class Google(val idToken: String) : SocialCredential
    data class Kakao(val accessToken: String) : SocialCredential
    data class Naver(val accessToken: String, val refreshToken: String) : SocialCredential
}

/** SDK 로그인 UI를 띄울 화면. 뷰모델이 Activity를 들고 있지 않도록 호출 시점에만 꺼낸다. */
fun interface SignInHost {
    fun activity(): Activity
}

/** 소셜 SDK 호출부. 테스트에서는 가짜로 대체한다. */
interface SocialSignInClient {
    val provider: LoginProvider

    /** 앱 키·클라이언트 ID가 주입됐는지. false면 버튼을 비활성화한다. */
    val isConfigured: Boolean

    /** 사용자가 취소하면 [SocialSignInCancelled], 그 밖의 실패는 [SocialSignInFailed]. */
    suspend fun signIn(host: SignInHost): SocialCredential

    suspend fun signOut() {}
}

class SocialSignInCancelled : Exception("Sign-in cancelled")

class SocialSignInFailed(message: String?, cause: Throwable? = null) : Exception(message, cause)
