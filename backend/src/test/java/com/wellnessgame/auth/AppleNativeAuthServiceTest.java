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

        SocialLoginResult result = authenticate(token(appleKey, KID, BUNDLE_ID, NOW.plusSeconds(600)), "임성규");

        assertThat(result.userId()).isEqualTo("apple:001234.abcdef.0420");
        assertThat(result.displayName()).isEqualTo("임성규");
        server.verify();
    }

    @Test
    void usesLocalizedDefaultNameWhenFullNameMissing() {
        expectJwks(jwks(KID, appleKey));
        String token = token(appleKey, KID, BUNDLE_ID, NOW.plusSeconds(600));

        assertThat(authenticate(token, null).displayName()).isEqualTo("Apple 사용자");
        LocaleContextHolder.setLocale(Locale.ENGLISH);
        assertThat(authenticate(token, "  ").displayName()).isEqualTo("Apple user");
    }

    @Test
    void rejectsTokenForAnotherApp() {
        expectJwks(jwks(KID, appleKey));

        assertThatThrownBy(() -> authenticate(token(appleKey, KID, "com.other.app", NOW.plusSeconds(600)), null))
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

        assertThatThrownBy(() -> authenticate(token, null))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("애플 identity token 발급자가 올바르지 않습니다.");
    }

    @Test
    void rejectsExpiredToken() {
        expectJwks(jwks(KID, appleKey));

        assertThatThrownBy(() -> authenticate(token(appleKey, KID, BUNDLE_ID, NOW.minusSeconds(1)), null))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("만료");
    }

    @Test
    void rejectsTokenWithoutExpiration() {
        expectJwks(jwks(KID, appleKey));
        String token = Jwts.builder().header().keyId(KID).and()
                .issuer(AppleNativeAuthService.APPLE_ISSUER).audience().single(BUNDLE_ID).subject("sub")
                .signWith(appleKey.getPrivate(), Jwts.SIG.RS256).compact();

        assertThatThrownBy(() -> authenticate(token, null)).isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void rejectsForgedSignatureUsingAppleKid() {
        expectJwks(jwks(KID, appleKey));

        assertThatThrownBy(() -> authenticate(token(attackerKey, KID, BUNDLE_ID, NOW.plusSeconds(600)), null))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("애플 identity token이 유효하지 않습니다.");
    }

    @Test
    void rejectsUnsignedOrMalformedToken() {
        String unsigned = Jwts.builder().issuer(AppleNativeAuthService.APPLE_ISSUER).audience().single(BUNDLE_ID)
                .subject("sub").expiration(Date.from(NOW.plusSeconds(600))).compact();

        assertThatThrownBy(() -> authenticate(unsigned, null)).isInstanceOf(UnauthorizedException.class);
        assertThatThrownBy(() -> authenticate("not-a-jwt", null)).isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void cachesJwksUntilTtlThenRefetches() {
        server.expect(ExpectedCount.twice(), requestTo(JWKS))
                .andRespond(withSuccess(jwks(KID, appleKey), MediaType.APPLICATION_JSON));

        authenticate(token(appleKey, KID, BUNDLE_ID, NOW.plusSeconds(7200)), null);
        authenticate(token(appleKey, KID, BUNDLE_ID, NOW.plusSeconds(7200)), null);
        clock.setInstant(NOW.plus(Duration.ofHours(1)).plusSeconds(1));
        authenticate(token(appleKey, KID, BUNDLE_ID, NOW.plusSeconds(7200)), null);

        server.verify();
    }

    @Test
    void refetchesOnUnknownKidOnlyAfterMinimumInterval() {
        expectJwks(jwks(KID, appleKey));
        authenticate(token(appleKey, KID, BUNDLE_ID, NOW.plusSeconds(600)), null);
        String rotated = token(rotatedKey, "apple-kid-2", BUNDLE_ID, NOW.plusSeconds(600));

        // 방금 받은 JWKS 에 없는 kid 는 곧바로 재조회하지 않는다(위조 kid 로 Apple 반복 호출 방지).
        assertThatThrownBy(() -> authenticate(rotated, null)).isInstanceOf(UnauthorizedException.class);
        server.verify();

        server.reset();
        expectJwks(jwks("apple-kid-2", rotatedKey));
        clock.setInstant(NOW.plus(JwksKeyCache.MIN_REFRESH_INTERVAL).plusSeconds(1));
        assertThat(authenticate(rotated, null).userId()).isEqualTo("apple:001234.abcdef.0420");
        server.verify();
    }

    @Test
    void truncatesOverlongFullNameByCodePoint() {
        expectJwks(jwks(KID, appleKey));
        String longName = "가".repeat(99) + "😀😀";

        String displayName = authenticate(token(appleKey, KID, BUNDLE_ID, NOW.plusSeconds(600)), longName)
                .displayName();

        assertThat(displayName).isEqualTo("가".repeat(99) + "😀");
    }

    @Test
    void keepsUsingExpiredCacheDuringOutageAndSuppressesRetries() {
        expectJwks(jwks(KID, appleKey));
        authenticate(token(appleKey, KID, BUNDLE_ID, NOW.plus(Duration.ofHours(3))), null);
        server.verify();

        // TTL 이 지난 뒤 Apple 장애: 기존 키로 계속 검증한다.
        server.reset();
        server.expect(requestTo(JWKS)).andRespond(withServerError());
        Instant outage = NOW.plus(Duration.ofHours(1)).plusSeconds(1);
        clock.setInstant(outage);
        String token = token(appleKey, KID, BUNDLE_ID, NOW.plus(Duration.ofHours(3)));
        assertThat(authenticate(token, null).userId()).isEqualTo("apple:001234.abcdef.0420");
        server.verify();

        // 백오프 동안은 Apple 을 다시 부르지 않는다(예상 밖 요청이면 MockRestServiceServer 가 실패시킨다).
        server.reset();
        clock.setInstant(outage.plus(JwksKeyCache.FAILURE_BACKOFF).minusSeconds(1));
        assertThat(authenticate(token, null).userId()).isEqualTo("apple:001234.abcdef.0420");
        server.verify();

        // 백오프가 지나면 다시 받는다.
        server.reset();
        expectJwks(jwks(KID, appleKey));
        clock.setInstant(outage.plus(JwksKeyCache.FAILURE_BACKOFF).plusSeconds(1));
        authenticate(token, null);
        server.verify();
    }

    @Test
    void failsFastWithoutAnyKeysDuringBackoff() {
        server.expect(requestTo(JWKS)).andRespond(withServerError());
        String token = token(appleKey, KID, BUNDLE_ID, NOW.plusSeconds(600));

        assertThatIllegalStateException().isThrownBy(() -> authenticate(token, null));
        server.verify();

        server.reset();
        clock.setInstant(NOW.plusSeconds(10));
        assertThatIllegalStateException().isThrownBy(() -> authenticate(token, null));
        server.verify();
    }

    @Test
    void reportsUnavailableWhenJwksFetchFails() {
        server.expect(requestTo(JWKS)).andRespond(withServerError());

        assertThatIllegalStateException()
                .isThrownBy(() -> authenticate(token(appleKey, KID, BUNDLE_ID, NOW.plusSeconds(600)), null));
    }

    @Test
    void reportsUnavailableWhenBundleIdMissing() {
        AppleNativeAuthService unconfigured = new AppleNativeAuthService(RestClient.builder(),
                new AppleNativeProperties("", null, null), clock);

        assertThatIllegalStateException()
                .isThrownBy(() -> unconfigured.authenticate("any", null, null))
                .withMessage("애플 로그인을 사용할 수 없습니다.");
    }

    // ---- nonce (#64) ----

    @Test
    void acceptsMatchingNonce() {
        expectJwks(jwks(KID, appleKey));
        String raw = "raw-nonce-123";

        SocialLoginResult result = service.authenticate(nonceToken(Sha256.hex(raw)), null, raw);

        assertThat(result.userId()).isEqualTo("apple:001234.abcdef.0420");
        // SHA-256("abc") 알려진 값으로 해시 규칙(소문자 hex)을 고정한다.
        assertThat(Sha256.hex("abc")).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void rejectsMismatchedNonce() {
        expectJwks(jwks(KID, appleKey));

        assertThatThrownBy(() -> service.authenticate(nonceToken(Sha256.hex("other")), null, "raw-nonce-123"))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("애플 identity token의 nonce가 일치하지 않습니다.");
    }

    @Test
    void rejectsNonceWhenTokenHasNoNonceClaimOrRawValue() {
        expectJwks(jwks(KID, appleKey));

        // 토큰에 nonce 클레임이 없는데 요청이 nonce 를 보냄
        assertThatThrownBy(() -> service.authenticate(token(appleKey, KID, BUNDLE_ID, NOW.plusSeconds(600)), null, "raw"))
                .isInstanceOf(UnauthorizedException.class);
        // 해시가 아닌 원문이 토큰에 들어 있으면 불일치
        assertThatThrownBy(() -> service.authenticate(nonceToken("raw"), null, "raw"))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void missingNonceIsAllowedUnlessRequired() {
        expectJwks(jwks(KID, appleKey));
        String token = nonceToken(Sha256.hex("raw"));

        assertThat(service.authenticate(token, null, null).userId()).isEqualTo("apple:001234.abcdef.0420");
        assertThat(service.authenticate(token, null, "").userId()).isEqualTo("apple:001234.abcdef.0420");
    }

    @Test
    void missingNonceIsRejectedWhenRequired() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer requiredServer = MockRestServiceServer.bindTo(builder).build();
        requiredServer.expect(requestTo(JWKS)).andRespond(withSuccess(jwks(KID, appleKey), MediaType.APPLICATION_JSON));
        AppleNativeAuthService required = new AppleNativeAuthService(builder,
                new AppleNativeProperties(BUNDLE_ID, JWKS, Duration.ofHours(1), true), clock);
        String token = nonceToken(Sha256.hex("raw"));

        assertThatThrownBy(() -> required.authenticate(token, null, null))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("애플 로그인 nonce가 필요합니다. 앱을 업데이트해 주세요.");
        assertThat(required.authenticate(token, null, "raw").userId()).isEqualTo("apple:001234.abcdef.0420");
    }

    private static String nonceToken(String nonceClaim) {
        return Jwts.builder()
                .header().keyId(KID).and()
                .issuer(AppleNativeAuthService.APPLE_ISSUER)
                .audience().single(BUNDLE_ID)
                .subject("001234.abcdef.0420")
                .claim("nonce", nonceClaim)
                .claim("nonce_supported", true)
                .expiration(Date.from(NOW.plusSeconds(600)))
                .signWith(appleKey.getPrivate(), Jwts.SIG.RS256)
                .compact();
    }

    private SocialLoginResult authenticate(String identityToken, String fullName) {
        return service.authenticate(identityToken, fullName, null);
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
