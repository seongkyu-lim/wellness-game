package com.wellnessgame.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * iOS Sign in with Apple identity token 검증 설정.
 *
 * @param bundleId     앱 번들 ID. identity token 의 aud 와 같아야 한다. 비어 있으면 503.
 * @param jwksUri      Apple 공개키(JWKS) URI
 * @param jwksCacheTtl JWKS 메모리 캐시 유지 시간. 지나면 다음 요청에서 다시 받는다.
 */
@ConfigurationProperties(prefix = "oauth.apple")
public record AppleNativeProperties(String bundleId, String jwksUri, Duration jwksCacheTtl) {
    public static final String DEFAULT_JWKS_URI = "https://appleid.apple.com/auth/keys";
    public static final Duration DEFAULT_JWKS_CACHE_TTL = Duration.ofHours(1);

    public AppleNativeProperties {
        bundleId = bundleId == null ? "" : bundleId.trim();
        if (jwksUri == null || jwksUri.isBlank()) {
            jwksUri = DEFAULT_JWKS_URI;
        }
        if (jwksCacheTtl == null || jwksCacheTtl.isNegative() || jwksCacheTtl.isZero()) {
            jwksCacheTtl = DEFAULT_JWKS_CACHE_TTL;
        }
    }

    public boolean configured() {
        return !bundleId.isEmpty();
    }
}
