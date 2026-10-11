package com.wellnessgame.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 회전 리프레시 토큰 설정(Auth v2).
 *
 * @param ttl              리프레시 토큰 유효 기간. 회전할 때마다 새 토큰이 이만큼 받는다. 비어 있으면 30일.
 * @param revokedRetention 폐기된 행을 재사용 감지용으로 남겨 두는 기간. 지나면 정리 작업이 지운다. 비어 있으면 7일.
 */
@ConfigurationProperties(prefix = "auth.refresh")
public record RefreshTokenProperties(Duration ttl, Duration revokedRetention) {
    public static final Duration DEFAULT_TTL = Duration.ofDays(30);
    public static final Duration DEFAULT_REVOKED_RETENTION = Duration.ofDays(7);

    public RefreshTokenProperties {
        if (ttl == null) {
            ttl = DEFAULT_TTL;
        }
        if (revokedRetention == null) {
            revokedRetention = DEFAULT_REVOKED_RETENTION;
        }
        if (ttl.toSeconds() < 1) {
            throw new IllegalStateException("auth.refresh.ttl(AUTH_REFRESH_TTL)은 1초 이상이어야 합니다.");
        }
        if (revokedRetention.isNegative()) {
            throw new IllegalStateException("auth.refresh.revoked-retention 은 0 이상이어야 합니다.");
        }
    }
}
