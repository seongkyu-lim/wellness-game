package com.wellnessgame.auth;

import com.wellnessgame.support.MutableClock;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AppleNativeAuthServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-04T00:00:00Z");
    private static final String JWKS = "https://apple.test/auth/keys";
    private static final String BUNDLE_ID = "com.wellnessgame.app";
    private static final String KID = "apple-kid-1";

    private static KeyPair appleKey;
    private static KeyPair rotatedKey;
    private static KeyPair attackerKey;

    private MutableClock clock;
    private MockRestServiceServer server;
    private AppleNativeAuthService service;

    @BeforeAll
    static void generateKeys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        appleKey = generator.generateKeyPair();
        rotatedKey = generator.generateKeyPair();
        attackerKey = generator.generateKeyPair();
    }

    @BeforeEach
    void setUp() {
        clock = new MutableClock(NOW);
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        service = new AppleNativeAuthService(builder,
                new AppleNativeProperties(BUNDLE_ID, JWKS, Duration.ofHours(1)), clock);
    }

    @AfterEach
    void resetLocale() {
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    void verifiesSignedTokenAndUsesSubjectWithFullName() {
        expectJwks(jwks(KID, appleKey));

        SocialLoginResult result = service.authenticate(token(appleKey, KID, BUNDLE_ID, NOW.plusSeconds(600)), "임성규");

        assertThat(result.userId()).isEqualTo("apple:001234.abcdef.0420");
        assertThat(result.displayName()).isEqualTo("임성규");
        server.verify();
    }

    @Test
    void usesLocalizedDefaultNameWhenFullNameMissing() {
        expectJwks(jwks(KID, appleKey));
        String token = token(appleKey, KID, BUNDLE_ID, NOW.plusSeconds(600));

        assertThat(service.authenticate(token, null).displayName()).isEqualTo("Apple 사용자");
        LocaleContextHolder.setLocale(Locale.ENGLISH);
        assertThat(service.authenticate(token, "  ").displayName()).isEqualTo("Apple user");
    }

    @Test
    void rejectsTokenForAnotherApp() {
        expectJwks(jwks(KID, appleKey));

        assertThatThrownBy(() -> service.authenticate(token(appleKey, KID, "com.other.app", NOW.plusSeconds(600)), null))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("이 앱에 발급된 애플 identity token이 아닙니다.");
    }

    @Test
    void rejectsUnexpectedIssuer() {
        expectJwks(jwks(KID, appleKey));
        String token = Jwts.builder().header().keyId(KID).and()
                .issuer("https://evil.test").audience().single(BUNDLE_ID)
                .subject("sub").expiration(Date.from(NOW.plusSeconds(600)))
                .signWith(appleKey.getPrivate(), Jwts.SIG.RS256).compact();

        assertThatThrownBy(() -> service.authenticate(token, null))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("애플 identity token 발급자가 올바르지 않습니다.");
    }

    @Test
    void rejectsExpiredToken() {
        expectJwks(jwks(KID, appleKey));

        assertThatThrownBy(() -> service.authenticate(token(appleKey, KID, BUNDLE_ID, NOW.minusSeconds(1)), null))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("만료");
    }

    @Test
    void rejectsTokenWithoutExpiration() {
        expectJwks(jwks(KID, appleKey));
        String token = Jwts.builder().header().keyId(KID).and()
                .issuer(AppleNativeAuthService.APPLE_ISSUER).audience().single(BUNDLE_ID).subject("sub")
                .signWith(appleKey.getPrivate(), Jwts.SIG.RS256).compact();

        assertThatThrownBy(() -> service.authenticate(token, null)).isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void rejectsForgedSignatureUsingAppleKid() {
        expectJwks(jwks(KID, appleKey));

        assertThatThrownBy(() -> service.authenticate(token(attackerKey, KID, BUNDLE_ID, NOW.plusSeconds(600)), null))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("애플 identity token이 유효하지 않습니다.");
    }

    @Test
    void rejectsUnsignedOrMalformedToken() {
        String unsigned = Jwts.builder().issuer(AppleNativeAuthService.APPLE_ISSUER).audience().single(BUNDLE_ID)
                .subject("sub").expiration(Date.from(NOW.plusSeconds(600))).compact();

        assertThatThrownBy(() -> service.authenticate(unsigned, null)).isInstanceOf(UnauthorizedException.class);
        assertThatThrownBy(() -> service.authenticate("not-a-jwt", null)).isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void cachesJwksUntilTtlThenRefetches() {
        server.expect(ExpectedCount.twice(), requestTo(JWKS))
                .andRespond(withSuccess(jwks(KID, appleKey), MediaType.APPLICATION_JSON));

        service.authenticate(token(appleKey, KID, BUNDLE_ID, NOW.plusSeconds(7200)), null);
        service.authenticate(token(appleKey, KID, BUNDLE_ID, NOW.plusSeconds(7200)), null);
        clock.setInstant(NOW.plus(Duration.ofHours(1)).plusSeconds(1));
        service.authenticate(token(appleKey, KID, BUNDLE_ID, NOW.plusSeconds(7200)), null);

        server.verify();
    }

    @Test
    void refetchesOnUnknownKidOnlyAfterMinimumInterval() {
        expectJwks(jwks(KID, appleKey));
        service.authenticate(token(appleKey, KID, BUNDLE_ID, NOW.plusSeconds(600)), null);
        String rotated = token(rotatedKey, "apple-kid-2", BUNDLE_ID, NOW.plusSeconds(600));

        // 방금 받은 JWKS 에 없는 kid 는 곧바로 재조회하지 않는다(위조 kid 로 Apple 반복 호출 방지).
        assertThatThrownBy(() -> service.authenticate(rotated, null)).isInstanceOf(UnauthorizedException.class);
        server.verify();

        server.reset();
        expectJwks(jwks("apple-kid-2", rotatedKey));
        clock.setInstant(NOW.plus(AppleNativeAuthService.MIN_REFRESH_INTERVAL).plusSeconds(1));
        assertThat(service.authenticate(rotated, null).userId()).isEqualTo("apple:001234.abcdef.0420");
        server.verify();
    }

    @Test
    void reportsUnavailableWhenJwksFetchFails() {
        server.expect(requestTo(JWKS)).andRespond(withServerError());

        assertThatIllegalStateException()
                .isThrownBy(() -> service.authenticate(token(appleKey, KID, BUNDLE_ID, NOW.plusSeconds(600)), null));
    }

    @Test
    void reportsUnavailableWhenBundleIdMissing() {
        AppleNativeAuthService unconfigured = new AppleNativeAuthService(RestClient.builder(),
                new AppleNativeProperties("", null, null), clock);

        assertThatIllegalStateException()
                .isThrownBy(() -> unconfigured.authenticate("any", null))
                .withMessage("애플 로그인을 사용할 수 없습니다.");
    }

    private void expectJwks(String body) {
        server.expect(requestTo(JWKS)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    /** Apple 은 aud 를 배열이 아닌 문자열로 준다(jjwt 의 audience().single 은 호환성 안내용 deprecated). */
    private static String token(KeyPair key, String kid, String audience, Instant expiration) {
        return Jwts.builder()
                .header().keyId(kid).and()
                .issuer(AppleNativeAuthService.APPLE_ISSUER)
                .audience().single(audience)
                .subject("001234.abcdef.0420")
                .claim("email", "relay@privaterelay.appleid.com")
                .issuedAt(Date.from(expiration.minusSeconds(3600)))
                .expiration(Date.from(expiration))
                .signWith(key.getPrivate(), Jwts.SIG.RS256)
                .compact();
    }

    /** Apple /auth/keys 와 같은 형식의 JWKS. */
    private static String jwks(String kid, KeyPair key) {
        RSAPublicKey publicKey = (RSAPublicKey) key.getPublic();
        return """
                {"keys":[{"kty":"RSA","kid":"%s","use":"sig","alg":"RS256","n":"%s","e":"%s"}]}
                """.formatted(kid, base64Url(publicKey.getModulus()), base64Url(publicKey.getPublicExponent()));
    }

    private static String base64Url(BigInteger value) {
        byte[] bytes = value.toByteArray();
        if (bytes.length > 1 && bytes[0] == 0) {
            bytes = Arrays.copyOfRange(bytes, 1, bytes.length);
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
