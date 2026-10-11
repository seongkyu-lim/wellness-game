package com.wellnessgame.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 회전 리프레시 토큰 설정(Auth v2).
 *
 * @param ttl              리프레시 토큰 유효 기간. 회전할 때마다 새로 시작하되 absoluteTtl 을 넘지 않는다. 비어 있으면 30일.
 * @param absoluteTtl      한 로그인(family)의 절대 수명. 회전해도 family 생성 시각 + 이 값을 넘지 못한다. 비어 있으면 90일.
 * @param reuseGrace       회전 유예 구간. 회전된 지 이 시간 안의 토큰이 다시 오면(응답 유실·동시 요청) 재사용으로 보지 않고
 *                         같은 family 에 형제 토큰을 발급한다. 비어 있으면 30초.
 * @param revokedRetention 폐기된 행을 재사용 감지용으로 남겨 두는 기간. 지나면 정리 작업이 지운다. 비어 있으면 7일.
 */
@ConfigurationProperties(prefix = "auth.refresh")
public record RefreshTokenProperties(Duration ttl, Duration absoluteTtl, Duration reuseGrace, Duration revokedRetention) {
    public static final Duration DEFAULT_TTL = Duration.ofDays(30);
    public static final Duration DEFAULT_ABSOLUTE_TTL = Duration.ofDays(90);
    public static final Duration DEFAULT_REUSE_GRACE = Duration.ofSeconds(30);
    public static final Duration DEFAULT_REVOKED_RETENTION = Duration.ofDays(7);

    public RefreshTokenProperties {
        ttl = ttl == null ? DEFAULT_TTL : ttl;
        absoluteTtl = absoluteTtl == null ? DEFAULT_ABSOLUTE_TTL : absoluteTtl;
        reuseGrace = reuseGrace == null ? DEFAULT_REUSE_GRACE : reuseGrace;
        revokedRetention = revokedRetention == null ? DEFAULT_REVOKED_RETENTION : revokedRetention;
        if (ttl.toSeconds() < 1) {
            throw new IllegalStateException("auth.refresh.ttl(AUTH_REFRESH_TTL)은 1초 이상이어야 합니다.");
        }
        if (absoluteTtl.compareTo(ttl) < 0) {
            throw new IllegalStateException("auth.refresh.absolute-ttl 은 auth.refresh.ttl 이상이어야 합니다.");
        }
        if (reuseGrace.isNegative() || revokedRetention.isNegative()) {
            throw new IllegalStateException("auth.refresh.reuse-grace·revoked-retention 은 0 이상이어야 합니다.");
        }
    }
}
