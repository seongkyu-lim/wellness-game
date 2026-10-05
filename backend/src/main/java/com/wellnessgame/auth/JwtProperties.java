package com.wellnessgame.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.time.Duration;

/**
 * 서버 발급 액세스 토큰(JWT) 설정. secret 은 HMAC-SHA256 키로 UTF-8 바이트 기준 32바이트 이상이어야 한다.
 *
 * @param issuer             발급 토큰의 iss. 검증 시 일치해야 한다. 비어 있으면 {@value #DEFAULT_ISSUER}.
 * @param audience           발급 토큰의 aud. 검증 시 포함돼야 한다. 비어 있으면 {@value #DEFAULT_AUDIENCE}.
 * @param acceptLegacyTokens true(기본)면 iss·aud 가 둘 다 없는 #55 이전 발급 토큰도 통과시킨다.
 *                           기존 토큰이 모두 만료(ttl 경과)되면 false 로 바꾸고 이후 옵션을 제거한다.
 */
@ConfigurationProperties(prefix = "auth.jwt")
public record JwtProperties(
        String secret,
        Duration ttl,
        String issuer,
        String audience,
        Boolean acceptLegacyTokens
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
        if (acceptLegacyTokens == null) {
            acceptLegacyTokens = true;
        }
    }

    public JwtProperties(String secret, Duration ttl) {
        this(secret, ttl, null, null, null);
    }
}
