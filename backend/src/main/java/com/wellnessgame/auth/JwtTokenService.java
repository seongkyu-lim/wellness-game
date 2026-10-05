package com.wellnessgame.auth;

import com.wellnessgame.i18n.Messages;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

/**
 * HMAC-SHA256 으로 서명한 액세스 토큰을 발급·검증한다. 클레임은 sub(userId), iat, exp 만 둔다.
 */
@Service
public class JwtTokenService {
    public static final int MIN_SECRET_BYTES = 32;
    private static final String ALGORITHM = "HS256";

    private final SecretKey key;
    private final Duration ttl;
    private final Clock clock;
    private final JwtParser parser;

    public JwtTokenService(JwtProperties properties, Clock clock) {
        String secret = properties.secret();
        byte[] secretBytes = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
        if (secretBytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "auth.jwt.secret(AUTH_JWT_SECRET)은 " + MIN_SECRET_BYTES + "바이트 이상이어야 합니다. 현재 "
                            + secretBytes.length + "바이트.");
        }
        if (properties.ttl().toSeconds() < 1) {
            throw new IllegalStateException("auth.jwt.ttl(AUTH_JWT_TTL)은 1초 이상이어야 합니다.");
        }
        this.key = Keys.hmacShaKeyFor(secretBytes);
        this.ttl = properties.ttl();
        this.clock = clock;
        this.parser = Jwts.parser()
                .verifyWith(key)
                .clock(() -> Date.from(this.clock.instant()))
                .build();
    }

    public IssuedToken issue(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("토큰 주체(userId)가 비어 있습니다.");
        }
        Instant now = clock.instant();
        String token = Jwts.builder()
                .subject(userId)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
        return new IssuedToken(token, IssuedToken.BEARER, ttl.toSeconds());
    }

    /**
     * 토큰을 검증하고 주체(sub)를 돌려준다.
     *
     * @throws UnauthorizedException 서명 불일치, 형식 오류, 만료, sub/exp 누락
     */
    public String verify(String token) {
        if (token == null || token.isBlank()) {
            throw new UnauthorizedException(Messages.get("auth.login-required"));
        }
        Jws<Claims> jws;
        try {
            jws = parser.parseSignedClaims(token);
        } catch (ExpiredJwtException e) {
            throw new UnauthorizedException(Messages.get("auth.token-expired"), e);
        } catch (JwtException | IllegalArgumentException e) {
            throw new UnauthorizedException(Messages.get("auth.token-invalid"), e);
        }
        Claims claims = jws.getPayload();
        if (!ALGORITHM.equals(jws.getHeader().getAlgorithm())
                || claims.getExpiration() == null
                || claims.getSubject() == null
                || claims.getSubject().isBlank()) {
            throw new UnauthorizedException(Messages.get("auth.token-invalid"));
        }
        return claims.getSubject();
    }

    public Duration ttl() {
        return ttl;
    }
}
