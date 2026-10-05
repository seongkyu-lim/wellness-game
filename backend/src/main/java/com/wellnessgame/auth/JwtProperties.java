package com.wellnessgame.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.time.Duration;
import java.time.Instant;

/**
 * 서버 발급 액세스 토큰(JWT) 설정. secret 은 HMAC-SHA256 키로 UTF-8 바이트 기준 32바이트 이상이어야 한다.
 *
 * @param issuer             발급 토큰의 iss. 검증 시 일치해야 한다. 비어 있으면 {@value #DEFAULT_ISSUER}.
 * @param audience           발급 토큰의 aud. 검증 시 포함돼야 한다. 비어 있으면 {@value #DEFAULT_AUDIENCE}.
 * @param legacyCutoff       iss·aud 가 둘 다 없는 #55 이전 형식 토큰은 iat 가 이 시각보다 이전일 때만 통과시킨다.
 *                           null(설정이 비어 있음)이면 이전 형식 토큰을 받지 않는다. 기본값은 application.yml 에 있다.
 *                           cutoff 이후 발급된 이전 형식 토큰은 위조로 보고 거부하며, 이전 토큰은 ttl 이 지나면 자연히 사라진다.
 */
@ConfigurationProperties(prefix = "auth.jwt")
public record JwtProperties(
        String secret,
        Duration ttl,
        String issuer,
        String audience,
        Instant legacyCutoff
) {
    public static final Duration DEFAULT_TTL = Duration.ofDays(30);
    public static final String DEFAULT_ISSUER = "wellness-game";
    public static final String DEFAULT_AUDIENCE = "wellness-game-api";

    @ConstructorBinding
    public JwtProperties {
        if (ttl == null) {
            ttl = DEFAULT_TTL;
        }
        if (issuer == null || issuer.isBlank()) {
            issuer = DEFAULT_ISSUER;
        }
        if (audience == null || audience.isBlank()) {
            audience = DEFAULT_AUDIENCE;
        }
    }

    public JwtProperties(String secret, Duration ttl) {
        this(secret, ttl, null, null, null);
    }
}
