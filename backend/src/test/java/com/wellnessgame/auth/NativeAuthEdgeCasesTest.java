package com.wellnessgame.auth;

import com.wellnessgame.error.BadRequestException;
import com.wellnessgame.error.ServiceUnavailableException;
import com.wellnessgame.support.MutableClock;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** 네이티브 로그인(Google/Kakao/Naver/Apple)의 입력 검증과 응답 이상 사례. */
class NativeAuthEdgeCasesTest {
    private static final Instant NOW = Instant.parse("2026-10-04T00:00:00Z");
    private static final String GOOGLE_JWKS = "https://google.test/certs";
    private static final String CLIENT = "ios.apps.googleusercontent.com";
    private static final String KID = "kid-1";
    private static KeyPair key;

    @BeforeAll
    static void generate() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        key = generator.generateKeyPair();
    }

    // ---- Google ----

    private GoogleNativeAuthService google(MockRestServiceServer[] serverOut) {
        RestClient.Builder builder = RestClient.builder();
        serverOut[0] = MockRestServiceServer.bindTo(builder).build();
        return new GoogleNativeAuthService(builder,
                new GoogleNativeProperties(List.of(CLIENT, " "), GOOGLE_JWKS, Duration.ofHours(1)), new MutableClock(NOW));
    }

    @Test
    void googleRejectsBlankIdTokenWithBadRequest() {
        MockRestServiceServer[] server = new MockRestServiceServer[1];
        GoogleNativeAuthService service = google(server);

        for (String blank : new String[]{null, "", "   "}) {
            assertThatThrownBy(() -> service.authenticate(blank)).isInstanceOf(BadRequestException.class);
        }
    }

    @Test
    void googleRejectsTokenWithoutSubjectOrAudience() {
        MockRestServiceServer[] server = new MockRestServiceServer[1];
        GoogleNativeAuthService service = google(server);
        server[0].expect(requestTo(GOOGLE_JWKS)).andRespond(withSuccess(jwks(), MediaType.APPLICATION_JSON));

        String noSub = Jwts.builder().header().keyId(KID).and().issuer("https://accounts.google.com")
                .audience().single(CLIENT).expiration(Date.from(NOW.plusSeconds(600)))
                .signWith(key.getPrivate(), Jwts.SIG.RS256).compact();
        String noAud = Jwts.builder().header().keyId(KID).and().issuer("https://accounts.google.com")
                .subject("s").expiration(Date.from(NOW.plusSeconds(600)))
                .signWith(key.getPrivate(), Jwts.SIG.RS256).compact();

        assertThatThrownBy(() -> service.authenticate(noSub)).isInstanceOf(UnauthorizedException.class);
        assertThatThrownBy(() -> service.authenticate(noAud)).isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void googleRejectsTokenWhenAnyAudienceIsNotAllowed() {
        MockRestServiceServer[] server = new MockRestServiceServer[1];
        GoogleNativeAuthService service = google(server);
        server[0].expect(requestTo(GOOGLE_JWKS)).andRespond(withSuccess(jwks(), MediaType.APPLICATION_JSON));

        String mixed = Jwts.builder().header().keyId(KID).and().issuer("https://accounts.google.com")
                .audience().add(CLIENT).add("evil.apps.googleusercontent.com").and()
                .subject("s").expiration(Date.from(NOW.plusSeconds(600)))
                .signWith(key.getPrivate(), Jwts.SIG.RS256).compact();

        assertThatThrownBy(() -> service.authenticate(mixed)).isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void googleDisplayNameIsNullWhenNameClaimIsNotAString() {
        MockRestServiceServer[] server = new MockRestServiceServer[1];
        GoogleNativeAuthService service = google(server);
        server[0].expect(requestTo(GOOGLE_JWKS)).andRespond(withSuccess(jwks(), MediaType.APPLICATION_JSON));

        String token = Jwts.builder().header().keyId(KID).and().issuer("accounts.google.com")
                .audience().single(CLIENT).subject("s").claim("name", 123)
                .expiration(Date.from(NOW.plusSeconds(600)))
                .signWith(key.getPrivate(), Jwts.SIG.RS256).compact();

        SocialLoginResult result = service.authenticate("  " + token + "  ");

        assertThat(result.userId()).isEqualTo("google:s");
        assertThat(result.displayName()).isNull();
    }

    // ---- Kakao ----

    private KakaoNativeAuthService kakao(MockRestServiceServer[] serverOut) {
        RestClient.Builder builder = RestClient.builder();
        serverOut[0] = MockRestServiceServer.bindTo(builder).build();
        OAuthProperties oauth = new OAuthProperties(
                new OAuthProperties.Provider("k", null, "https://k.test/token", "https://k.test/me"), null, null);
        return new KakaoNativeAuthService(builder, new SocialAuthService(builder, oauth),
                new KakaoNativeProperties("111", "https://k.test/info"));
    }

    @Test
    void kakaoRejectsBlankAccessToken() {
        MockRestServiceServer[] server = new MockRestServiceServer[1];
        KakaoNativeAuthService service = kakao(server);

        assertThatThrownBy(() -> service.authenticate(" ")).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.authenticate(null)).isInstanceOf(BadRequestException.class);
    }

    @Test
    void kakaoRejectsEmptyTokenInfoBodyAndUnauthorizedStatus() {
        MockRestServiceServer[] server = new MockRestServiceServer[1];
        KakaoNativeAuthService service = kakao(server);
        server[0].expect(requestTo("https://k.test/info")).andRespond(withSuccess("null", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> service.authenticate("t")).isInstanceOf(UnauthorizedException.class);

        server[0].reset();
        server[0].expect(requestTo("https://k.test/info")).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        assertThatThrownBy(() -> service.authenticate("t")).isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void kakaoRejectsProfileWhoseIdIsMissing() {
        MockRestServiceServer[] server = new MockRestServiceServer[1];
        KakaoNativeAuthService service = kakao(server);
        server[0].expect(requestTo("https://k.test/info"))
                .andRespond(withSuccess("{\"id\":5,\"expires_in\":100,\"app_id\":\"111\"}", MediaType.APPLICATION_JSON));
        server[0].expect(requestTo("https://k.test/me")).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> service.authenticate("t")).isInstanceOf(UnauthorizedException.class);
    }

    // ---- Naver ----

    private NaverNativeAuthService naver(MockRestServiceServer[] serverOut, OAuthProperties.Provider naver) {
        RestClient.Builder builder = RestClient.builder();
        serverOut[0] = MockRestServiceServer.bindTo(builder).build();
        OAuthProperties oauth = new OAuthProperties(null, naver, null);
        return new NaverNativeAuthService(builder, new SocialAuthService(builder, oauth), oauth);
    }

    @Test
    void naverRejectsMissingTokenUriProfileUriOrNullProperties() {
        MockRestServiceServer[] server = new MockRestServiceServer[1];
        for (OAuthProperties.Provider provider : new OAuthProperties.Provider[]{
                null,
                new OAuthProperties.Provider("id", "secret", null, "https://n.test/me"),
                new OAuthProperties.Provider("id", "secret", "https://n.test/token", " ")}) {
            NaverNativeAuthService service = naver(server, provider);
            assertThatThrownBy(() -> service.authenticate("a", "r")).isInstanceOf(ServiceUnavailableException.class);
        }
    }

    @Test
    void naverRejectsBlankAccessToken() {
        MockRestServiceServer[] server = new MockRestServiceServer[1];
        NaverNativeAuthService service = naver(server,
                new OAuthProperties.Provider("id", "secret", "https://n.test/token", "https://n.test/me"));

        assertThatThrownBy(() -> service.authenticate(" ", "r")).isInstanceOf(BadRequestException.class);
    }

    @Test
    void naverRejectsEmptyTokenResponse() {
        MockRestServiceServer[] server = new MockRestServiceServer[1];
        NaverNativeAuthService service = naver(server,
                new OAuthProperties.Provider("id", "secret", "https://n.test/token", "https://n.test/me"));
        server[0].expect(requestTo("https://n.test/token")).andRespond(withSuccess("{\"access_token\":\"\"}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> service.authenticate("a", "r")).isInstanceOf(UnauthorizedException.class);
    }

    // ---- Apple ----

    @Test
    void appleRejectsBlankIdentityTokenAndAcceptsSubjectWithBlankFullName() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AppleNativeAuthService service = new AppleNativeAuthService(builder,
                new AppleNativeProperties("com.app", "https://apple.test/keys", Duration.ofHours(1)), new MutableClock(NOW));
        assertThatThrownBy(() -> service.authenticate(" ", null, null)).isInstanceOf(BadRequestException.class);

        server.expect(requestTo("https://apple.test/keys")).andRespond(withSuccess(jwks(), MediaType.APPLICATION_JSON));
        String noSub = Jwts.builder().header().keyId(KID).and().issuer("https://appleid.apple.com")
                .audience().single("com.app").expiration(Date.from(NOW.plusSeconds(600)))
                .signWith(key.getPrivate(), Jwts.SIG.RS256).compact();
        assertThatThrownBy(() -> service.authenticate(noSub, null, null)).isInstanceOf(UnauthorizedException.class);
    }

    private static String jwks() {
        RSAPublicKey pub = (RSAPublicKey) key.getPublic();
        return "{\"keys\":[{\"kty\":\"RSA\",\"kid\":\"%s\",\"use\":\"sig\",\"alg\":\"RS256\",\"n\":\"%s\",\"e\":\"%s\"}]}"
                .formatted(KID, b64(pub.getModulus()), b64(pub.getPublicExponent()));
    }

    private static String b64(BigInteger value) {
        byte[] bytes = value.toByteArray();
        if (bytes.length > 1 && bytes[0] == 0) {
            bytes = Arrays.copyOfRange(bytes, 1, bytes.length);
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
