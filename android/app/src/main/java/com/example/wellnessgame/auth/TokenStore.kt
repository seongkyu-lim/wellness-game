package com.example.wellnessgame.auth

import kotlinx.serialization.Serializable

/** 서버가 발급한 세션. userId는 서버 응답 값만 쓰며 앱이 조합하지 않는다. */
@Serializable
data class StoredSession(
    val userId: String,
    val displayName: String? = null,
    /** [com.example.wellnessgame.domain.LoginProvider.storageKey] */
    val provider: String,
    val accessToken: String,
    /** 만료 시각(epoch 초). 서버가 expiresIn을 주지 않으면 null. */
    val expiresAtEpochSeconds: Long? = null,
)

/** 세션(토큰 포함) 영속 저장소. 구현은 Keystore로 암호화한다. 테스트는 메모리 가짜를 쓴다. */
interface TokenStore {
    suspend fun load(): StoredSession?
    suspend fun save(session: StoredSession)
    suspend fun clear()
}
