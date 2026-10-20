package com.wellnessgame.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.time.Duration;
import java.util.List;

/**
 * 네이티브(iOS·Android) 구글 로그인 ID 토큰 검증 설정. clientIds 가 허용 aud·azp 목록의 전부다: iOS 클라이언트 ID,
 * Android 클라이언트 ID, Android 가 aud 로 쓰는 전용 웹 유형 "서버" 클라이언트 ID. 웹 로그인 클라이언트
 * ID(oauth.google.client-id)는 자동으로 포함하지 않으며 여기에 넣지도 않는다.
 *
 * @param jwksUri      Google 공개키(JWKS) URI
 * @param jwksCacheTtl JWKS 메모리 캐시 유지 시간
 * @param jwksStaleMax JWKS 조회 실패 시 마지막으로 받은 키를 쓰는 최대 기간. 비어 있으면 24시간.
 */
@ConfigurationProperties(prefix = "oauth.google-native")
public record GoogleNativeProperties(List<String> clientIds, String jwksUri, Duration jwksCacheTtl, Duration jwksStaleMax) {
    public static final String DEFAULT_JWKS_URI = "https://www.googleapis.com/oauth2/v3/certs";
    public static final Duration DEFAULT_JWKS_CACHE_TTL = Duration.ofHours(1);
    public static final Duration DEFAULT_JWKS_STALE_MAX = Duration.ofHours(24);

    @ConstructorBinding
    public GoogleNativeProperties {
        clientIds = clientIds == null ? List.of() : List.copyOf(clientIds);
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

    public GoogleNativeProperties(List<String> clientIds, String jwksUri, Duration jwksCacheTtl) {
        this(clientIds, jwksUri, jwksCacheTtl, null);
    }
}
