package com.wellnessgame.auth;

import com.wellnessgame.support.MutableClock;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtTokenServiceTest {
    private static final String SECRET = "unit-test-jwt-secret-0123456789abcdef";
    private static final Instant NOW = Instant.parse("2026-10-04T00:00:00Z");
    private static final Instant CUTOFF = Instant.parse("2026-10-06T00:00:00Z");

    private final MutableClock clock = new MutableClock(NOW);
    private final JwtTokenService service = new JwtTokenService(new JwtProperties(SECRET, Duration.ofDays(30)), clock);

    @Test
    void issuesBearerTokenWithSubjectIssuerAudienceIatAndExp() {
        IssuedToken token = service.issue("google:123");

        assertThat(token.tokenType()).isEqualTo("Bearer");
        assertThat(token.expiresIn()).isEqualTo(2_592_000L);
        assertThat(service.verify(token.accessToken())).isEqualTo("google:123");

        String[] parts = token.accessToken().split("\\.");
        String header = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        assertThat(header).contains("\"alg\":\"HS256\"");
        assertThat(payload).isEqualTo("{\"sub\":\"google:123\",\"iss\":\"wellness-game\",\"aud\":[\"wellness-game-api\"],\"iat\":" + NOW.getEpochSecond()
                + ",\"exp\":" + NOW.plus(Duration.ofDays(30)).getEpochSecond() + "}");
    }

    @Test
    void defaultsTtlToThirtyDays() {
        JwtTokenService defaults = new JwtTokenService(new JwtProperties(SECRET, null), clock);

        assertThat(defaults.issue("password:abc").expiresIn()).isEqualTo(2_592_000L);
    }

    @Test
    void acceptsTokenJustBeforeExpiry() {
        String token = service.issue("google:123").accessToken();
        clock.setInstant(NOW.plus(Duration.ofDays(30)).minusSeconds(1));

        assertThat(service.verify(token)).isEqualTo("google:123");
    }

    @Test
    void rejectsExpiredToken() {
        String token = service.issue("google:123").accessToken();
        clock.setInstant(NOW.plus(Duration.ofDays(30)).plusSeconds(1));

        assertThatThrownBy(() -> service.verify(token))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("만료");
    }

    @Test
    void rejectsTokenSignedWithAnotherKey() {
        JwtTokenService other = new JwtTokenService(
                new JwtProperties("another-secret-of-sufficient-length-xyz", Duration.ofDays(30)), clock);
        String forged = other.issue("google:123").accessToken();

        assertThatThrownBy(() -> service.verify(forged))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("유효하지 않습니다");
    }

    @Test
    void rejectsTamperedPayload() {
        String[] parts = service.issue("google:123").accessToken().split("\\.");
        String tamperedPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("{\"sub\":\"google:999\",\"iat\":" + NOW.getEpochSecond() + ",\"exp\":"
                        + NOW.plus(Duration.ofDays(30)).getEpochSecond() + "}").getBytes(StandardCharsets.UTF_8));
        String tampered = parts[0] + "." + tamperedPayload + "." + parts[2];

        assertThatThrownBy(() -> service.verify(tampered)).isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void rejectsUnsignedToken() {
        String unsigned = Jwts.builder()
                .subject("google:123")
                .issuedAt(Date.from(NOW))
                .expiration(Date.from(NOW.plusSeconds(60)))
                .compact();

        assertThatThrownBy(() -> service.verify(unsigned)).isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void rejectsTokenWithoutExpiration() {
        String noExp = Jwts.builder()
                .subject("google:123")
                .issuedAt(Date.from(NOW))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)), Jwts.SIG.HS256)
                .compact();

        assertThatThrownBy(() -> service.verify(noExp)).isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void rejectsGarbageAndBlankTokens() {
        assertThatThrownBy(() -> service.verify("not-a-jwt")).isInstanceOf(UnauthorizedException.class);
        assertThatThrownBy(() -> service.verify("")).isInstanceOf(UnauthorizedException.class);
        assertThatThrownBy(() -> service.verify(null)).isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void failsFastWhenSecretShorterThan32Bytes() {
        assertThatIllegalStateException()
                .isThrownBy(() -> new JwtTokenService(new JwtProperties("x".repeat(31), Duration.ofDays(30)), clock))
                .withMessageContaining("32바이트");
    }

    @Test
    void failsFastWhenSecretMissing() {
        assertThatIllegalStateException()
                .isThrownBy(() -> new JwtTokenService(new JwtProperties("", Duration.ofDays(30)), clock));
        assertThatIllegalStateException()
                .isThrownBy(() -> new JwtTokenService(new JwtProperties(null, Duration.ofDays(30)), clock));
    }

    @Test
    void accepts32ByteSecret() {
        JwtTokenService minimal = new JwtTokenService(
                new JwtProperties("0123456789abcdefghijklmnopqrstuv", Duration.ofDays(30)), clock);

        assertThat(minimal.verify(minimal.issue("google:1").accessToken())).isEqualTo("google:1");
    }

    @Test
    void failsFastWhenTtlNotPositive() {
        assertThatIllegalStateException()
                .isThrownBy(() -> new JwtTokenService(new JwtProperties(SECRET, Duration.ZERO), clock));
    }

    // ---- #55 iss/aud 와 약한 시크릿 ----

    @Test
    void acceptsLegacyTokenIssuedBeforeCutoff() {
        JwtTokenService withCutoff = serviceWithCutoff(CUTOFF);
        clock.setInstant(CUTOFF.plus(Duration.ofDays(10)));

        assertThat(withCutoff.verify(legacyToken("password:abc", CUTOFF.minusSeconds(1)))).isEqualTo("password:abc");
    }

    @Test
    void rejectsLegacyTokenIssuedAtOrAfterCutoff() {
        JwtTokenService withCutoff = serviceWithCutoff(CUTOFF);
        clock.setInstant(CUTOFF.plusSeconds(10));

        assertThatThrownBy(() -> withCutoff.verify(legacyToken("password:abc", CUTOFF)))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("유효하지 않습니다");
        assertThatThrownBy(() -> withCutoff.verify(legacyToken("password:abc", CUTOFF.plusSeconds(1))))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void rejectsLegacyTokenWithoutIssuedAt() {
        String noIat = Jwts.builder()
                .subject("password:abc")
                .expiration(Date.from(NOW.plusSeconds(60)))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)), Jwts.SIG.HS256)
                .compact();

        assertThatThrownBy(() -> serviceWithCutoff(CUTOFF).verify(noIat)).isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void rejectsAllLegacyTokensWhenCutoffNotSet() {
        assertThatThrownBy(() -> service.verify(legacyToken("password:abc", NOW.minusSeconds(60))))
                .isInstanceOf(UnauthorizedException.class);
        assertThat(service.verify(service.issue("password:abc").accessToken())).isEqualTo("password:abc");
    }

    @Test
    void blankCutoffPropertyBindsToNoLegacyTokens() {
        JwtProperties blank = new Binder(new MapConfigurationPropertySource(Map.of(
                "auth.jwt.secret", SECRET, "auth.jwt.legacy-cutoff", "")))
                .bind("auth.jwt", JwtProperties.class).get();
        JwtProperties set = new Binder(new MapConfigurationPropertySource(Map.of(
                "auth.jwt.secret", SECRET, "auth.jwt.legacy-cutoff", "2026-10-06T00:00:00Z")))
                .bind("auth.jwt", JwtProperties.class).get();

        assertThat(blank.legacyCutoff()).isNull();
        assertThat(set.legacyCutoff()).isEqualTo(Instant.parse("2026-10-06T00:00:00Z"));
    }

    @Test
    void rejectsTokenFromAnotherIssuerOrAudience() {
        JwtTokenService otherIssuer = new JwtTokenService(
                new JwtProperties(SECRET, Duration.ofDays(30), "someone-else", null, CUTOFF), clock);
        JwtTokenService otherAudience = new JwtTokenService(
                new JwtProperties(SECRET, Duration.ofDays(30), null, "another-api", CUTOFF), clock);

        assertThatThrownBy(() -> service.verify(otherIssuer.issue("google:1").accessToken()))
                .isInstanceOf(UnauthorizedException.class);
        assertThatThrownBy(() -> service.verify(otherAudience.issue("google:1").accessToken()))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void rejectsTokenWithOnlyOneOfIssuerOrAudienceEvenInLegacyMode() {
        String issuerOnly = Jwts.builder()
                .subject("google:1")
                .issuer("wellness-game")
                .issuedAt(Date.from(NOW))
                .expiration(Date.from(NOW.plusSeconds(60)))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)), Jwts.SIG.HS256)
                .compact();

        assertThatThrownBy(() -> serviceWithCutoff(CUTOFF).verify(issuerOnly)).isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void defaultsBlankIssuerAndAudience() {
        JwtProperties properties = new JwtProperties(SECRET, null, " ", "", null);

        assertThat(properties.issuer()).isEqualTo("wellness-game");
        assertThat(properties.audience()).isEqualTo("wellness-game-api");
        assertThat(properties.legacyCutoff()).isNull();
    }

    @Test
    void failsFastWhenSecretIsTrivial() {
        for (String weak : new String[]{"x".repeat(32), "ab".repeat(20), "1234".repeat(10), "abcdefg".repeat(5)}) {
            assertThatIllegalStateException()
                    .as(weak)
                    .isThrownBy(() -> new JwtTokenService(new JwtProperties(weak, Duration.ofDays(30)), clock))
                    .withMessageContaining("너무 단순")
                    .withMessageNotContaining(weak);
        }
    }

    @Test
    void acceptsProjectFixedSecrets() {
        for (String fixed : new String[]{SECRET, "test-only-jwt-secret-0123456789abcdef",
                "local-dev-only-jwt-secret-change-me-0123456789"}) {
            new JwtTokenService(new JwtProperties(fixed, Duration.ofDays(30)), clock);
        }
    }

    private JwtTokenService serviceWithCutoff(Instant cutoff) {
        return new JwtTokenService(new JwtProperties(SECRET, Duration.ofDays(30), null, null, cutoff), clock);
    }

    private static String legacyToken(String subject, Instant issuedAt) {
        return Jwts.builder()
                .subject(subject)
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(issuedAt.plus(Duration.ofDays(30))))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)), Jwts.SIG.HS256)
                .compact();
    }
}
