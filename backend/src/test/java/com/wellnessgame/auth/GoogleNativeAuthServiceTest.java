package com.wellnessgame.auth;

import com.wellnessgame.support.MutableClock;
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import java.util.List;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Google ID 토큰 JWKS 로컬 검증(#55). 테스트용 RSA 키로 서명한 토큰을 쓴다. */
class GoogleNativeAuthServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-04T00:00:00Z");
    private static final String JWKS = "https://google.test/oauth2/v3/certs";
    private static final String IOS_CLIENT = "ios-client.apps.googleusercontent.com";
    private static final String WEB_CLIENT = "web-client.apps.googleusercontent.com";
    private static final String KID = "google-kid-1";

    private static KeyPair googleKey;
    private static KeyPair rotatedKey;
    private static KeyPair attackerKey;

    private MutableClock clock;
    private MockRestServiceServer server;
    private GoogleNativeAuthService service;

    @BeforeAll
    static void generateKeys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        googleKey = generator.generateKeyPair();
        rotatedKey = generator.generateKeyPair();
        attackerKey = generator.generateKeyPair();
    }

    @BeforeEach
    void setUp() {
        clock = new MutableClock(NOW);
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        service = new GoogleNativeAuthService(
                builder,
                oauth(WEB_CLIENT),
                new GoogleNativeProperties(List.of(IOS_CLIENT), JWKS, Duration.ofHours(1)),
                clock);
    }

    @Test
    void verifiesIosIdTokenAndUsesGoogleSubjectUserId() {
        expectJwks(jwks(KID, googleKey));

        SocialLoginResult result = service.authenticate(token(b -> b));

        assertThat(result.userId()).isEqualTo("google:109876");
        assertThat(result.displayName()).isEqualTo("SK Lim");
        server.verify();
    }

    @Test
    void acceptsWebClientAudienceWithIosAzpAndBareIssuer() {
        expectJwks(jwks(KID, googleKey));

        String token = token(b -> b.issuer("accounts.google.com").audience().single(WEB_CLIENT).claim("azp", IOS_CLIENT));

        assertThat(service.authenticate(token).userId()).isEqualTo("google:109876");
    }

    @Test
    void rejectsAudienceMismatch() {
        expectJwks(jwks(KID, googleKey));

        assertThatThrownBy(() -> service.authenticate(token(b -> b.audience().single("someone-else.apps.googleusercontent.com"))))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("이 앱에 발급된 구글 ID 토큰이 아닙니다.");
    }

    @Test
    void rejectsAzpMismatch() {
        expectJwks(jwks(KID, googleKey));

        assertThatThrownBy(() -> service.authenticate(token(b -> b.claim("azp", "other-app.apps.googleusercontent.com"))))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("이 앱에 발급된 구글 ID 토큰이 아닙니다.");
    }

    @Test
    void rejectsUnexpectedIssuer() {
        expectJwks(jwks(KID, googleKey));

        assertThatThrownBy(() -> service.authenticate(token(b -> b.issuer("https://evil.test"))))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("구글 ID 토큰 발급자가 올바르지 않습니다.");
    }

    @Test
    void rejectsExpiredIdToken() {
        expectJwks(jwks(KID, googleKey));

        assertThatThrownBy(() -> service.authenticate(token(b -> b.expiration(Date.from(NOW.minusSeconds(1))))))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("만료");
    }

    @Test
    void rejectsIdTokenExpiringExactlyNow() {
        expectJwks(jwks(KID, googleKey));

        assertThatThrownBy(() -> service.authenticate(token(b -> b.expiration(Date.from(NOW)))))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void rejectsTokenWithoutExpiration() {
        expectJwks(jwks(KID, googleKey));

        assertThatThrownBy(() -> service.authenticate(token(b -> b.expiration(null))))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void rejectsForgedSignatureUsingGoogleKid() {
        expectJwks(jwks(KID, googleKey));

        assertThatThrownBy(() -> service.authenticate(token(attackerKey, KID, b -> b)))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("구글 ID 토큰이 유효하지 않습니다.");
    }

    @Test
    void rejectsNonRs256AndMalformedTokens() {
        String unsigned = Jwts.builder().issuer("https://accounts.google.com").audience().single(IOS_CLIENT)
                .subject("109876").expiration(Date.from(NOW.plusSeconds(600))).compact();

        assertThatThrownBy(() -> service.authenticate(unsigned)).isInstanceOf(UnauthorizedException.class);
        assertThatThrownBy(() -> service.authenticate("not-a-jwt")).isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void refetchesJwksForUnknownKidAfterMinimumInterval() {
        expectJwks(jwks(KID, googleKey));
        service.authenticate(token(b -> b));
        server.verify();
        String rotated = token(rotatedKey, "google-kid-2", b -> b);

        // 방금 받은 JWKS 에 없는 kid 는 곧바로 재조회하지 않는다.
        assertThatThrownBy(() -> service.authenticate(rotated)).isInstanceOf(UnauthorizedException.class);
        server.verify();

        server.reset();
        expectJwks(jwks("google-kid-2", rotatedKey));
        clock.setInstant(NOW.plus(JwksKeyCache.MIN_REFRESH_INTERVAL).plusSeconds(1));
        assertThat(service.authenticate(rotated).userId()).isEqualTo("google:109876");
        server.verify();
    }

    @Test
    void usesStaleKeysWhenJwksUnavailable() {
        expectJwks(jwks(KID, googleKey));
        Instant later = NOW.plus(Duration.ofHours(2));
        String token = token(b -> b.expiration(Date.from(later.plusSeconds(600))));
        service.authenticate(token);
        server.verify();

        // 캐시 TTL 이 지난 뒤 Google 장애: 기존 키로 계속 검증하고, 백오프 동안 재시도하지 않는다.
        server.reset();
        server.expect(requestTo(JWKS)).andRespond(withServerError());
        clock.setInstant(later);
        assertThat(service.authenticate(token).userId()).isEqualTo("google:109876");
        clock.setInstant(later.plus(JwksKeyCache.FAILURE_BACKOFF).minusSeconds(1));
        assertThat(service.authenticate(token).userId()).isEqualTo("google:109876");
        server.verify();
    }

    /** stale-on-error 는 마지막 조회 후 jwks-stale-max(기본 24시간)까지만. 넘으면 503. */
    @Test
    void stopsUsingStaleKeysAfterStaleMax() {
        expectJwks(jwks(KID, googleKey));
        Instant staleLimit = NOW.plus(GoogleNativeProperties.DEFAULT_JWKS_STALE_MAX);
        String token = token(b -> b.expiration(Date.from(staleLimit.plus(Duration.ofHours(1)))));
        service.authenticate(token);
        server.verify();

        server.reset();
        server.expect(ExpectedCount.twice(), requestTo(JWKS)).andRespond(withServerError());
        clock.setInstant(staleLimit);
        assertThat(service.authenticate(token).userId()).isEqualTo("google:109876");

        clock.setInstant(staleLimit.plusSeconds(1).plus(JwksKeyCache.FAILURE_BACKOFF));
        assertThatIllegalStateException()
                .isThrownBy(() -> service.authenticate(token))
                .withMessageContaining("구글 ID 토큰을 검증하지 못했습니다");
        // 재시도 억제 구간에도 stale-max 를 넘은 키는 쓰지 않는다.
        clock.setInstant(staleLimit.plusSeconds(2).plus(JwksKeyCache.FAILURE_BACKOFF));
        assertThatIllegalStateException().isThrownBy(() -> service.authenticate(token));
        server.verify();
    }

    @Test
    void reportsUnavailableWhenJwksFetchFailsWithoutCachedKeys() {
        server.expect(requestTo(JWKS)).andRespond(withServerError());

        assertThatIllegalStateException()
                .isThrownBy(() -> service.authenticate(token(b -> b)))
                .withMessageContaining("구글 ID 토큰을 검증하지 못했습니다");
    }

    @Test
    void reportsUnavailableWhenNoClientIdConfigured() {
        GoogleNativeAuthService unconfigured = new GoogleNativeAuthService(
                RestClient.builder(),
                new OAuthProperties(null, null, null),
                new GoogleNativeProperties(null, null, null),
                clock);

        assertThatIllegalStateException().isThrownBy(() -> unconfigured.authenticate("id-token"));
    }

    private void expectJwks(String body) {
        server.expect(requestTo(JWKS)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    private static String token(UnaryOperator<JwtBuilder> customizer) {
        return token(googleKey, KID, customizer);
    }

    /** iOS Google Sign-In ID 토큰 형식: aud·azp = iOS 클라이언트 ID. customizer 로 클레임을 바꾼다. */
    private static String token(KeyPair key, String kid, UnaryOperator<JwtBuilder> customizer) {
        JwtBuilder builder = Jwts.builder()
                .header().keyId(kid).and()
                .issuer("https://accounts.google.com")
                .audience().single(IOS_CLIENT)
                .claim("azp", IOS_CLIENT)
                .subject("109876")
                .claim("name", "SK Lim")
                .claim("email", "sk@example.com")
                .issuedAt(Date.from(NOW.minusSeconds(60)))
                .expiration(Date.from(NOW.plusSeconds(3600)));
        return customizer.apply(builder).signWith(key.getPrivate(), Jwts.SIG.RS256).compact();
    }

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

    private static OAuthProperties oauth(String webClientId) {
        return new OAuthProperties(null, null,
                new OAuthProperties.Provider(webClientId, "secret", "https://google.test/token", "https://google.test/userinfo"));
    }
}
