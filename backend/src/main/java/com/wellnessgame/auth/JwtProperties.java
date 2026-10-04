package com.wellnessgame.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 서버 발급 액세스 토큰(JWT) 설정. secret 은 HMAC-SHA256 키로 UTF-8 바이트 기준 32바이트 이상이어야 한다.
 */
@ConfigurationProperties(prefix = "auth.jwt")
public record JwtProperties(String secret, Duration ttl) {
    public static final Duration DEFAULT_TTL = Duration.ofDays(30);

    public JwtProperties {
        if (ttl == null) {
            ttl = DEFAULT_TTL;
        }
    }
}
