package com.wellnessgame.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.time.Duration;

/**
 * iOS Sign in with Apple identity token 검증 설정.
 *
 * @param bundleId     앱 번들 ID. identity token 의 aud 와 같아야 한다. 비어 있으면 503.
 * @param jwksUri      Apple 공개키(JWKS) URI
 * @param jwksCacheTtl JWKS 메모리 캐시 유지 시간. 지나면 다음 요청에서 다시 받는다.
 * @param requireNonce true 면 nonce 없는 요청을 401 로 거부한다(#64). nonce 를 보내는 앱이 배포된 뒤 켠다.
 * @param jwksStaleMax JWKS 조회 실패 시 마지막으로 받은 키를 쓰는 최대 기간. 비어 있으면 24시간.
 */
@ConfigurationProperties(prefix = "oauth.apple")
public record AppleNativeProperties(
        String bundleId,
        String jwksUri,
        Duration jwksCacheTtl,
        boolean requireNonce,
        Duration jwksStaleMax
) {
    public static final String DEFAULT_JWKS_URI = "https://appleid.apple.com/auth/keys";
    public static final Duration DEFAULT_JWKS_CACHE_TTL = Duration.ofHours(1);
    public static final Duration DEFAULT_JWKS_STALE_MAX = Duration.ofHours(24);

    @ConstructorBinding
    public AppleNativeProperties {
        bundleId = bundleId == null ? "" : bundleId.trim();
        if (jwksUri == null || jwksUri.isBlank()) {
            jwksUri = DEFAULT_JWKS_URI;
        }
        if (jwksCacheTtl == null || jwksCacheTtl.isNegative() || jwksCacheTtl.isZero()) {
            jwksCacheTtl = DEFAULT_JWKS_CACHE_TTL;
        }
        if (jwksStaleMax == null || jwksStaleMax.isNegative()) {
            jwksStaleMax = DEFAULT_JWKS_STALE_MAX;
        }
    }

    public AppleNativeProperties(String bundleId, String jwksUri, Duration jwksCacheTtl, boolean requireNonce) {
        this(bundleId, jwksUri, jwksCacheTtl, requireNonce, null);
    }

    public AppleNativeProperties(String bundleId, String jwksUri, Duration jwksCacheTtl) {
        this(bundleId, jwksUri, jwksCacheTtl, false, null);
    }

    public boolean configured() {
        return !bundleId.isEmpty();
    }
}
