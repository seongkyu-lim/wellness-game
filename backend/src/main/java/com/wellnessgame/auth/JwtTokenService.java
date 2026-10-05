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
import java.util.Set;

/**
 * HMAC-SHA256 으로 서명한 액세스 토큰을 발급·검증한다. 클레임은 sub(userId), iat, exp, iss, aud 를 둔다.
 *
 * <p>iss·aud 는 검증 때 설정값과 일치해야 한다. 단 #55 이전에 발급돼 iss·aud 가 둘 다 없는 토큰은
 * iat 가 {@code auth.jwt.legacy-cutoff} 보다 이전일 때만 통과시켜 기존 사용자가 로그아웃되지 않게 한다.
 */
@Service
public class JwtTokenService {
    public static final int MIN_SECRET_BYTES = 32;
    /** 이보다 적은 종류의 문자로만 된 시크릿(같은 문자 반복, 짧은 패턴 반복 등)은 약한 키로 보고 기동을 막는다. */
    static final int MIN_DISTINCT_SECRET_CHARS = 8;
    private static final String ALGORITHM = "HS256";

    private final SecretKey key;
    private final Duration ttl;
    private final Clock clock;
    private final JwtParser parser;
    private final String issuer;
    private final String audience;
    private final Instant legacyCutoff;

    public JwtTokenService(JwtProperties properties, Clock clock) {
        String secret = properties.secret();
        byte[] secretBytes = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
        if (secretBytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "auth.jwt.secret(AUTH_JWT_SECRET)은 " + MIN_SECRET_BYTES + "바이트 이상이어야 합니다. 현재 "
                            + secretBytes.length + "바이트.");
        }
        if (secret.chars().distinct().count() < MIN_DISTINCT_SECRET_CHARS) {
            // 값 자체는 로그·예외 메시지에 남기지 않는다.
            throw new IllegalStateException("auth.jwt.secret(AUTH_JWT_SECRET)이 너무 단순합니다. 서로 다른 문자가 "
                    + MIN_DISTINCT_SECRET_CHARS + "종류 이상인 무작위 값을 쓰세요(예: openssl rand -base64 48).");
        }
        if (properties.ttl().toSeconds() < 1) {
            throw new IllegalStateException("auth.jwt.ttl(AUTH_JWT_TTL)은 1초 이상이어야 합니다.");
        }
        this.key = Keys.hmacShaKeyFor(secretBytes);
        this.ttl = properties.ttl();
        this.clock = clock;
        this.issuer = properties.issuer();
        this.audience = properties.audience();
        this.legacyCutoff = properties.legacyCutoff();
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
                .issuer(issuer)
                .audience().add(audience).and()
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
        return new IssuedToken(token, IssuedToken.BEARER, ttl.toSeconds());
    }

    /**
     * 토큰을 검증하고 주체(sub)를 돌려준다.
     *
     * @throws UnauthorizedException 서명 불일치, 형식 오류, 만료, sub/exp 누락, iss/aud 불일치
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
                || claims.getSubject().isBlank()
                || !hasExpectedIssuerAndAudience(claims)) {
            throw new UnauthorizedException(Messages.get("auth.token-invalid"));
        }
        return claims.getSubject();
    }

    /** iss·aud 가 설정값과 맞거나, 둘 다 없는 이전 형식 토큰이고 cutoff 이전에 발급된 경우. */
    private boolean hasExpectedIssuerAndAudience(Claims claims) {
        String tokenIssuer = claims.getIssuer();
        Set<String> tokenAudience = claims.getAudience();
        boolean noAudience = tokenAudience == null || tokenAudience.isEmpty();
        if (tokenIssuer == null && noAudience) {
            Date issuedAt = claims.getIssuedAt();
            return legacyCutoff != null && issuedAt != null && issuedAt.toInstant().isBefore(legacyCutoff);
        }
        return issuer.equals(tokenIssuer) && !noAudience && tokenAudience.contains(audience);
    }

    public Duration ttl() {
        return ttl;
    }
}
