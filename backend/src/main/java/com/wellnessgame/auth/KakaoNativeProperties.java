package com.wellnessgame.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * iOS 카카오 로그인 액세스 토큰 검증 설정. {@link OAuthProperties} 의 oauth.kakao 와 같은 prefix 를 공유하며
 * 네이티브 전용 키만 읽는다.
 *
 * @param nativeAppId          카카오 앱 ID(숫자). access_token_info 의 app_id 와 같아야 한다. 비어 있으면 503.
 * @param accessTokenInfoUri   토큰 정보 조회 URI
 */
@ConfigurationProperties(prefix = "oauth.kakao")
public record KakaoNativeProperties(String nativeAppId, String accessTokenInfoUri) {
    public static final String DEFAULT_ACCESS_TOKEN_INFO_URI = "https://kapi.kakao.com/v1/user/access_token_info";

    public KakaoNativeProperties {
        nativeAppId = nativeAppId == null ? "" : nativeAppId.trim();
        if (accessTokenInfoUri == null || accessTokenInfoUri.isBlank()) {
            accessTokenInfoUri = DEFAULT_ACCESS_TOKEN_INFO_URI;
        }
    }

    public boolean configured() {
        return !nativeAppId.isEmpty();
    }
}
