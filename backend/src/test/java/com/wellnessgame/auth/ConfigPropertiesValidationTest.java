package com.wellnessgame.auth;

import com.wellnessgame.ratelimit.RateLimitProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/** 설정 레코드의 기본값 채움과 기동 시 검증(잘못된 값은 기동 실패). */
class ConfigPropertiesValidationTest {

    private static RefreshTokenProperties refresh(Duration ttl, Duration absolute, Duration grace,
                                                  Duration retention, Duration readTtl, Duration readAbsolute) {
        return new RefreshTokenProperties(ttl, absolute, grace, retention, readTtl, readAbsolute);
    }

    @Test
    void refreshTokenPropertiesFillDefaultsWhenEmpty() {
        RefreshTokenProperties p = refresh(null, null, null, null, null, null);

        assertThat(p.ttl()).isEqualTo(Duration.ofDays(30));
        assertThat(p.absoluteTtl()).isEqualTo(Duration.ofDays(90));
        assertThat(p.reuseGrace()).isEqualTo(Duration.ofSeconds(30));
        assertThat(p.revokedRetention()).isEqualTo(Duration.ofDays(7));
        assertThat(p.readTtl()).isEqualTo(Duration.ofDays(7));
        assertThat(p.readAbsoluteTtl()).isEqualTo(Duration.ofDays(30));
    }

    @Test
    void refreshTokenPropertiesPickLifetimeByScope() {
        RefreshTokenProperties p = refresh(null, null, null, null, null, null);

        assertThat(p.ttl(TokenScope.FULL)).isEqualTo(p.ttl());
        assertThat(p.ttl(TokenScope.READ)).isEqualTo(p.readTtl());
        assertThat(p.absoluteTtl(TokenScope.FULL)).isEqualTo(p.absoluteTtl());
        assertThat(p.absoluteTtl(TokenScope.READ)).isEqualTo(p.readAbsoluteTtl());
    }

    @Test
    void refreshTokenPropertiesRejectTtlBelowOneSecond() {
        assertThatIllegalStateException()
                .isThrownBy(() -> refresh(Duration.ofMillis(999), Duration.ofDays(1), null, null, null, null));
        assertThatIllegalStateException()
                .isThrownBy(() -> refresh(Duration.ZERO, Duration.ofDays(1), null, null, null, null));
        assertThatIllegalStateException()
                .isThrownBy(() -> refresh(null, null, null, null, Duration.ofMillis(500), Duration.ofDays(1)));
        // 경계: 정확히 1초는 통과
        assertThatCode(() -> refresh(Duration.ofSeconds(1), Duration.ofSeconds(1), null, null, null, null))
                .doesNotThrowAnyException();
    }

    @Test
    void refreshTokenPropertiesRejectAbsoluteTtlShorterThanIdleTtl() {
        assertThatIllegalStateException()
                .isThrownBy(() -> refresh(Duration.ofDays(10), Duration.ofDays(9), null, null, null, null));
        assertThatIllegalStateException()
                .isThrownBy(() -> refresh(null, null, null, null, Duration.ofDays(10), Duration.ofDays(9)));
        // 경계: 같으면 통과
        assertThatCode(() -> refresh(Duration.ofDays(10), Duration.ofDays(10), null, null, null, null))
                .doesNotThrowAnyException();
    }

    @Test
    void refreshTokenPropertiesRejectNegativeGraceAndRetention() {
        assertThatIllegalStateException()
                .isThrownBy(() -> refresh(null, null, Duration.ofSeconds(-1), null, null, null));
        assertThatIllegalStateException()
                .isThrownBy(() -> refresh(null, null, null, Duration.ofSeconds(-1), null, null));
        // 0 은 허용(유예·보관 없음)
        assertThatCode(() -> refresh(null, null, Duration.ZERO, Duration.ZERO, null, null))
                .doesNotThrowAnyException();
    }

    @Test
    void jwtPropertiesFillDefaults() {
        JwtProperties p = new JwtProperties("s".repeat(32), null, " ", null, null, null);

        assertThat(p.ttl()).isEqualTo(JwtProperties.DEFAULT_TTL);
        assertThat(p.issuer()).isEqualTo(JwtProperties.DEFAULT_ISSUER);
        assertThat(p.audience()).isEqualTo(JwtProperties.DEFAULT_AUDIENCE);
        assertThat(p.watchTtl()).isEqualTo(JwtProperties.DEFAULT_WATCH_TTL);
        assertThat(p.legacyCutoff()).isNull();
    }

    @Test
    void jwtPropertiesKeepExplicitValuesAndConvenienceConstructors() {
        Instant cutoff = Instant.parse("2026-01-01T00:00:00Z");
        JwtProperties p = new JwtProperties("s".repeat(32), Duration.ofMinutes(5), "iss", "aud", cutoff);

        assertThat(p.ttl()).isEqualTo(Duration.ofMinutes(5));
        assertThat(p.issuer()).isEqualTo("iss");
        assertThat(p.audience()).isEqualTo("aud");
        assertThat(p.legacyCutoff()).isEqualTo(cutoff);
        assertThat(p.watchTtl()).isEqualTo(JwtProperties.DEFAULT_WATCH_TTL);
        assertThat(new JwtProperties("s".repeat(32), Duration.ofMinutes(5)).issuer()).isEqualTo(JwtProperties.DEFAULT_ISSUER);
    }

    @Test
    void googleNativePropertiesFillDefaultsAndCopyClientIds() {
        GoogleNativeProperties p = new GoogleNativeProperties(null, " ", Duration.ZERO, Duration.ofSeconds(-1));

        assertThat(p.clientIds()).isEmpty();
        assertThat(p.jwksUri()).isEqualTo(GoogleNativeProperties.DEFAULT_JWKS_URI);
        assertThat(p.jwksCacheTtl()).isEqualTo(GoogleNativeProperties.DEFAULT_JWKS_CACHE_TTL);
        assertThat(p.jwksStaleMax()).isEqualTo(GoogleNativeProperties.DEFAULT_JWKS_STALE_MAX);
        assertThat(new GoogleNativeProperties(null, null, Duration.ofSeconds(-5)).jwksCacheTtl())
                .isEqualTo(GoogleNativeProperties.DEFAULT_JWKS_CACHE_TTL);
    }

    @Test
    void googleNativePropertiesKeepExplicitValuesAndAllowZeroStaleMax() {
        List<String> ids = new java.util.ArrayList<>(List.of("a"));
        GoogleNativeProperties p = new GoogleNativeProperties(ids, "https://x.test/certs", Duration.ofMinutes(3), Duration.ZERO);
        ids.add("b"); // 원본 목록을 바꿔도 영향 없어야 한다

        assertThat(p.clientIds()).containsExactly("a");
        assertThat(p.jwksUri()).isEqualTo("https://x.test/certs");
        assertThat(p.jwksCacheTtl()).isEqualTo(Duration.ofMinutes(3));
        assertThat(p.jwksStaleMax()).isEqualTo(Duration.ZERO);
    }

    @Test
    void appleNativePropertiesTrimBundleIdAndFillDefaults() {
        AppleNativeProperties blank = new AppleNativeProperties("  ", null, null, false, Duration.ofSeconds(-1));
        AppleNativeProperties set = new AppleNativeProperties(" com.app ", "", Duration.ofSeconds(-1));

        assertThat(blank.configured()).isFalse();
        assertThat(new AppleNativeProperties(null, null, null).bundleId()).isEmpty();
        assertThat(blank.jwksUri()).isEqualTo(AppleNativeProperties.DEFAULT_JWKS_URI);
        assertThat(blank.jwksStaleMax()).isEqualTo(AppleNativeProperties.DEFAULT_JWKS_STALE_MAX);
        assertThat(set.configured()).isTrue();
        assertThat(set.bundleId()).isEqualTo("com.app");
        assertThat(set.jwksCacheTtl()).isEqualTo(AppleNativeProperties.DEFAULT_JWKS_CACHE_TTL);
        assertThat(new AppleNativeProperties("a", null, Duration.ofMinutes(2), true).requireNonce()).isTrue();
        assertThat(new AppleNativeProperties("a", null, Duration.ofMinutes(2), true, Duration.ZERO).jwksStaleMax())
                .isEqualTo(Duration.ZERO);
    }

    @Test
    void kakaoNativePropertiesTrimAndDefault() {
        assertThat(new KakaoNativeProperties(null, null).configured()).isFalse();
        assertThat(new KakaoNativeProperties("  ", " ").accessTokenInfoUri())
                .isEqualTo(KakaoNativeProperties.DEFAULT_ACCESS_TOKEN_INFO_URI);
        KakaoNativeProperties p = new KakaoNativeProperties(" 12345 ", "https://k.test/info");
        assertThat(p.configured()).isTrue();
        assertThat(p.nativeAppId()).isEqualTo("12345");
        assertThat(p.accessTokenInfoUri()).isEqualTo("https://k.test/info");
    }

    @Test
    void oauthProviderIsConfiguredOnlyWithNonBlankClientId() {
        assertThat(new OAuthProperties.Provider(null, "s", "t", "p").configured()).isFalse();
        assertThat(new OAuthProperties.Provider("  ", "s", "t", "p").configured()).isFalse();
        assertThat(new OAuthProperties.Provider("id", null, null, null).configured()).isTrue();
    }

    @Test
    void loginLockoutPropertiesRequirePositiveMinutesWhenEnabled() {
        assertThatIllegalStateException().isThrownBy(() -> new LoginLockoutProperties(5, 0, 0));
        assertThatIllegalStateException().isThrownBy(() -> new LoginLockoutProperties(0, 50, 0));
        // 둘 다 꺼졌으면 분이 0이어도 된다.
        assertThatCode(() -> new LoginLockoutProperties(0, 0, 0)).doesNotThrowAnyException();
        assertThat(new LoginLockoutProperties(5, 50, 15).lockout()).isEqualTo(Duration.ofMinutes(15));
        assertThat(LoginLockoutProperties.DEFAULTS.maxFailedLogins()).isEqualTo(5);
    }

    @Test
    void rateLimitPropertiesRejectInvalidValues() {
        assertThatIllegalStateException().isThrownBy(() -> rateLimit(0, 0));
        assertThatIllegalStateException().isThrownBy(() -> rateLimit(1, -1));
        assertThatCode(() -> rateLimit(1, 0)).doesNotThrowAnyException();
    }

    private static RateLimitProperties rateLimit(int maxKeys, int hops) {
        return new RateLimitProperties(30, 30, 10, 120, false, hops, false, maxKeys);
    }

    @Test
    void tokenScopeMapsClaimAndColumnValues() {
        assertThat(TokenScope.fromClaim(null)).isEqualTo(TokenScope.FULL);
        assertThat(TokenScope.fromClaim("read")).isEqualTo(TokenScope.READ);
        // 모르는 값은 거부(null)된다. 대소문자도 구분한다.
        assertThat(TokenScope.fromClaim("admin")).isNull();
        assertThat(TokenScope.fromClaim("READ")).isNull();
        assertThat(TokenScope.fromClaim(1)).isNull();

        assertThat(TokenScope.fromColumn("full")).isEqualTo(TokenScope.FULL);
        assertThat(TokenScope.fromColumn("read")).isEqualTo(TokenScope.READ);
        assertThatIllegalStateException().isThrownBy(() -> TokenScope.fromColumn("write"));
        assertThatIllegalStateException().isThrownBy(() -> TokenScope.fromColumn(null));
        assertThat(TokenScope.FULL.claimValue()).isNull();
        assertThat(TokenScope.READ.claimValue()).isEqualTo("read");
    }

    @Test
    void tokenScopeConverterRoundTrips() {
        TokenScopeConverter converter = new TokenScopeConverter();

        assertThat(converter.convertToDatabaseColumn(TokenScope.READ)).isEqualTo("read");
        assertThat(converter.convertToEntityAttribute("full")).isEqualTo(TokenScope.FULL);
        assertThat(converter.convertToDatabaseColumn(TokenScope.FULL)).isEqualTo("full");
        // 값이 없으면 전체 권한으로 본다(컬럼 기본값과 같다).
        assertThat(converter.convertToDatabaseColumn(null)).isEqualTo("full");
        assertThat(converter.convertToEntityAttribute(null)).isEqualTo(TokenScope.FULL);
    }

    @Test
    void sha256HexIsStableLowercaseHex() {
        assertThat(Sha256.hex("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        assertThat(Sha256.hex("")).hasSize(64);
    }
}
