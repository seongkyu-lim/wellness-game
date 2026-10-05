package com.wellnessgame.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * iOS 구글 로그인 ID 토큰 검증 설정. clientIds 는 iOS 클라이언트 ID 목록이며,
 * 웹 클라이언트 ID(oauth.google.client-id)와 함께 허용 aud 목록을 이룬다.
 */
@ConfigurationProperties(prefix = "oauth.google-native")
public record GoogleNativeProperties(List<String> clientIds, String tokeninfoUri) {
    public static final String DEFAULT_TOKENINFO_URI = "https://oauth2.googleapis.com/tokeninfo";

    public GoogleNativeProperties {
        clientIds = clientIds == null ? List.of() : List.copyOf(clientIds);
        if (tokeninfoUri == null || tokeninfoUri.isBlank()) {
            tokeninfoUri = DEFAULT_TOKENINFO_URI;
        }
    }
}
