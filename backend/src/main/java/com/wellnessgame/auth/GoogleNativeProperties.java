package com.wellnessgame.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * iOS 구글 로그인 ID 토큰 검증 설정. clientIds 는 iOS 클라이언트 ID 목록이며,
 * 웹 클라이언트 ID(oauth.google.client-id)와 함께 허용 aud·azp 목록을 이룬다.
 *
 * @param jwksUri      Google 공개키(JWKS) URI
 * @param jwksCacheTtl JWKS 메모리 캐시 유지 시간
 */
@ConfigurationProperties(prefix = "oauth.google-native")
public record GoogleNativeProperties(List<String> clientIds, String jwksUri, Duration jwksCacheTtl) {
    public static final String DEFAULT_JWKS_URI = "https://www.googleapis.com/oauth2/v3/certs";
    public static final Duration DEFAULT_JWKS_CACHE_TTL = Duration.ofHours(1);

    public GoogleNativeProperties {
        clientIds = clientIds == null ? List.of() : List.copyOf(clientIds);
        if (jwksUri == null || jwksUri.isBlank()) {
            jwksUri = DEFAULT_JWKS_URI;
        }
        if (jwksCacheTtl == null || jwksCacheTtl.isNegative() || jwksCacheTtl.isZero()) {
            jwksCacheTtl = DEFAULT_JWKS_CACHE_TTL;
        }
    }
}
