package com.wellnessgame.auth;

import com.wellnessgame.support.MutableClock;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 발급 입력 검증과 검증 시 헤더·클레임 가장자리. */
class JwtTokenServiceEdgeCasesTest {
    private static final String SECRET = "edge-case-jwt-secret-0123456789abcdef";
    private static final Instant NOW = Instant.parse("2026-10-04T00:00:00Z");

    private final MutableClock clock = new MutableClock(NOW);
    private final JwtTokenService service = new JwtTokenService(new JwtProperties(SECRET, Duration.ofHours(1)), clock);
    private final SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));

    @Test
    void issueRejectsNullOrBlankSubject() {
        assertThatIllegalArgumentException().isThrownBy(() -> service.issue(null));
        assertThatIllegalArgumentException().isThrownBy(() -> service.issue("  "));
        assertThatIllegalArgumentException().isThrownBy(() -> service.issueReadOnly(""));
        assertThatIllegalArgumentException().isThrownBy(() -> service.issue(null, TokenScope.READ));
    }

    @Test
    void authenticateReturnsScopeAndIssuedAtTruncatedToSeconds() {
        clock.setInstant(NOW.plusMillis(789));
        AccessTokenPrincipal full = service.authenticate(service.issue("u1").accessToken());
        AccessTokenPrincipal read = service.authenticate(service.issue("u1", TokenScope.READ).accessToken());

        assertThat(full.scope()).isEqualTo(TokenScope.FULL);
        assertThat(read.scope()).isEqualTo(TokenScope.READ);
        assertThat(full.issuedAt()).isEqualTo(NOW);   // JWT iat 는 초 단위
    }

    @Test
    void rejectsTokenSignedWithAnotherHmacAlgorithm() {
        SecretKey longKey = Keys.hmacShaKeyFor((SECRET + SECRET).getBytes(StandardCharsets.UTF_8));
        String hs512 = Jwts.builder().subject("u").issuer("wellness-game").audience().add("wellness-game-api").and()
                .issuedAt(Date.from(NOW)).expiration(Date.from(NOW.plusSeconds(60)))
                .signWith(longKey, Jwts.SIG.HS512).compact();

        // 같은 키 재료로 서명해도 키 길이에 따라 HS256 이 아니면 받지 않는다(검증 키가 다르거나 알고리즘이 달라 401).
        assertThatThrownBy(() -> service.authenticate(hs512)).isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void rejectsTokenWithoutOrBlankSubject() {
        String noSubject = Jwts.builder().issuer("wellness-game").audience().add("wellness-game-api").and()
                .issuedAt(Date.from(NOW)).expiration(Date.from(NOW.plusSeconds(60))).signWith(key).compact();
        String blankSubject = Jwts.builder().subject(" ").issuer("wellness-game").audience().add("wellness-game-api").and()
                .issuedAt(Date.from(NOW)).expiration(Date.from(NOW.plusSeconds(60))).signWith(key).compact();

        assertThatThrownBy(() -> service.authenticate(noSubject)).isInstanceOf(UnauthorizedException.class);
        assertThatThrownBy(() -> service.authenticate(blankSubject)).isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void acceptsTokenWithMultipleAudiencesContainingExpectedOne() {
        String token = Jwts.builder().subject("u").issuer("wellness-game")
                .audience().add("other").add("wellness-game-api").and()
                .issuedAt(Date.from(NOW)).expiration(Date.from(NOW.plusSeconds(60))).signWith(key).compact();

        assertThat(service.verify(token)).isEqualTo("u");
    }

    @Test
    void rejectsTokenWithIssuerButNoAudience() {
        String token = Jwts.builder().subject("u").issuer("wellness-game")
                .issuedAt(Date.from(NOW)).expiration(Date.from(NOW.plusSeconds(60))).signWith(key).compact();

        assertThatThrownBy(() -> service.authenticate(token)).isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void rejectsNonStringScopeClaim() {
        String token = Jwts.builder().subject("u").issuer("wellness-game").audience().add("wellness-game-api").and()
                .claim("scope", 1).issuedAt(Date.from(NOW)).expiration(Date.from(NOW.plusSeconds(60))).signWith(key).compact();

        assertThatThrownBy(() -> service.authenticate(token)).isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void ttlAccessorReturnsConfiguredValue() {
        assertThat(service.ttl()).isEqualTo(Duration.ofHours(1));
    }

    @Test
    void tokenIsRejectedOnceExpirationSecondHasPassed() {
        String token = service.issue("u").accessToken();

        clock.setInstant(NOW.plus(Duration.ofHours(1)).minusSeconds(1));
        assertThat(service.verify(token)).isEqualTo("u");
        // jjwt 는 exp 와 같은 순간까지는 받아 주므로(RFC 7519 는 exp 이후 거부) 1초 지난 뒤부터 거부를 기대한다.
        clock.setInstant(NOW.plus(Duration.ofHours(1)).plusSeconds(1));
        assertThatThrownBy(() -> service.verify(token)).isInstanceOf(UnauthorizedException.class);
    }
}
