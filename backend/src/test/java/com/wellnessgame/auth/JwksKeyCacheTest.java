package com.wellnessgame.auth;

import com.wellnessgame.support.MutableClock;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import javax.crypto.SecretKey;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** JWKS 캐시 단위 테스트: 헤더 검증, 캐시 TTL·재조회 간격·장애 백오프 경계, 잘못된 JWKS 본문. */
class JwksKeyCacheTest {
    private static final Instant NOW = Instant.parse("2026-10-04T00:00:00Z");
    private static final String URI = "https://idp.test/keys";
    private static final Duration TTL = Duration.ofHours(1);
    private static final Duration STALE_MAX = Duration.ofHours(24);

    private static KeyPair key;
    private static KeyPair otherKey;

    private MutableClock clock;
    private MockRestServiceServer server;
    private JwksKeyCache cache;

    @BeforeAll
    static void generate() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        key = generator.generateKeyPair();
        otherKey = generator.generateKeyPair();
    }

    @BeforeEach
    void setUp() {
        clock = new MutableClock(NOW);
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        cache = new JwksKeyCache("Test", builder.build(), URI, TTL, STALE_MAX, clock);
    }

    @Test
    void verifiesTokenWithCachedKeyAndFetchesOnlyOnce() {
        server.expect(ExpectedCount.once(), requestTo(URI)).andRespond(withSuccess(jwks("k1"), MediaType.APPLICATION_JSON));
        String token = rs256Token("k1");

        assertThat(parse(token)).isEqualTo("sub");
        assertThat(parse(token)).isEqualTo("sub");
        server.verify();
    }

    @Test
    void cacheTtlBoundaryRefetchesExactlyAtTtl() {
        server.expect(requestTo(URI)).andRespond(withSuccess(jwks("k1"), MediaType.APPLICATION_JSON));
        String token = rs256Token("k1");
        parse(token);

        // TTL 직전까지는 캐시
        clock.setInstant(NOW.plus(TTL).minusMillis(1));
        parse(token);
        server.verify();

        // 정확히 TTL 이 되면 다시 받는다
        server.reset();
        server.expect(requestTo(URI)).andRespond(withSuccess(jwks("k1"), MediaType.APPLICATION_JSON));
        clock.setInstant(NOW.plus(TTL));
        parse(token);
        server.verify();
    }

    @Test
    void rejectsNonRs256Algorithm() {
        SecretKey hmac = Jwts.SIG.HS256.key().build();
        String token = Jwts.builder().header().keyId("k1").and().subject("sub").signWith(hmac).compact();

        assertThatThrownBy(() -> parse(token)).isInstanceOf(JwtException.class).hasMessageContaining("algorithm");
        server.verify(); // JWKS 를 받지도 않는다
    }

    @Test
    void rejectsMissingOrBlankKeyId() {
        for (String kid : new String[]{null, " "}) {
            String token = kid == null
                    ? Jwts.builder().subject("sub").signWith(key.getPrivate(), Jwts.SIG.RS256).compact()
                    : Jwts.builder().header().keyId(kid).and().subject("sub")
                            .signWith(key.getPrivate(), Jwts.SIG.RS256).compact();
            assertThatThrownBy(() -> parse(token)).isInstanceOf(JwtException.class).hasMessageContaining("key id");
        }
    }

    @Test
    void unknownKidWithinMinRefreshIntervalDoesNotRefetchAndAfterItDoes() {
        server.expect(requestTo(URI)).andRespond(withSuccess(jwks("k1"), MediaType.APPLICATION_JSON));
        parse(rs256Token("k1"));

        assertThatThrownBy(() -> parse(rs256Token("forged"))).isInstanceOf(JwtException.class)
                .hasMessageContaining("Unknown");
        server.verify();

        // 정확히 최소 간격이 지나면 키 교체 대응으로 다시 받는다.
        server.reset();
        server.expect(requestTo(URI)).andRespond(withSuccess(jwksWithOther("k1", "k2"), MediaType.APPLICATION_JSON));
        clock.setInstant(NOW.plus(JwksKeyCache.MIN_REFRESH_INTERVAL));
        assertThat(parse(otherToken("k2"))).isEqualTo("sub");
        server.verify();
    }

    @Test
    void failureBackoffSuppressesRetriesUntilItElapses() {
        server.expect(requestTo(URI)).andRespond(withSuccess(jwks("k1"), MediaType.APPLICATION_JSON));
        String token = rs256Token("k1");
        parse(token);

        clock.setInstant(NOW.plus(TTL));
        server.reset();
        server.expect(ExpectedCount.once(), requestTo(URI)).andRespond(withServerError());
        assertThat(parse(token)).isEqualTo("sub"); // stale 키로 계속 검증
        // 백오프 안 : 재조회 없음(위 expect 가 once 라 추가 호출이면 실패한다)
        clock.setInstant(NOW.plus(TTL).plus(JwksKeyCache.FAILURE_BACKOFF).minusMillis(1));
        assertThat(parse(token)).isEqualTo("sub");
        server.verify();

        // 백오프가 끝나면 복구된 JWKS 를 받는다
        server.reset();
        server.expect(requestTo(URI)).andRespond(withSuccess(jwksWithOther("k1", "k3"), MediaType.APPLICATION_JSON));
        clock.setInstant(NOW.plus(TTL).plus(JwksKeyCache.FAILURE_BACKOFF));
        assertThat(parse(otherToken("k3"))).isEqualTo("sub");
        server.verify();
    }

    @Test
    void backoffWithoutAnyCachedKeysReportsUnavailable() {
        server.expect(ExpectedCount.once(), requestTo(URI)).andRespond(withServerError());

        assertThatThrownBy(() -> parse(rs256Token("k1"))).isInstanceOf(JwksKeyCache.UnavailableException.class);
        // 백오프 중에는 호출 없이 바로 실패
        assertThatThrownBy(() -> parse(rs256Token("k1"))).isInstanceOf(JwksKeyCache.UnavailableException.class)
                .hasRootCauseMessage("Test JWKS 재시도 대기 중");
        server.verify();
    }

    @Test
    void invalidJwksBodiesAreUnavailable() {
        String[] bodies = {
                "",                         // 빈 본문
                "not json",                 // JSON 아님
                "{\"keys\":[]}",            // 키 없음
                // RSA 가 아닌 키(EC)만 있음
                "{\"keys\":[{\"kty\":\"EC\",\"kid\":\"e1\",\"crv\":\"P-256\","
                        + "\"x\":\"f83OJ3D2xF1Bg8vub9tLe1gHMzV76e8Tus9uPHvRVEU\","
                        + "\"y\":\"x_FEzRu9m36HLN_tue659LNpXW6pCyStikYjKIWI5a0\"}]}",
        };
        for (String body : bodies) {
            server.reset();
            // 백오프 때문에 케이스마다 새 캐시가 필요하다
            RestClient.Builder builder = RestClient.builder();
            MockRestServiceServer s = MockRestServiceServer.bindTo(builder).build();
            JwksKeyCache fresh = new JwksKeyCache("Test", builder.build(), URI, TTL, STALE_MAX, clock);
            s.expect(requestTo(URI)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

            assertThatThrownBy(() -> Jwts.parser().keyLocator(fresh.locator()).build().parseSignedClaims(rs256Token("k1")))
                    .as(body).isInstanceOf(JwksKeyCache.UnavailableException.class);
        }
    }

    @Test
    void keysWithoutIdAreIgnored() {
        RSAPublicKey pub = (RSAPublicKey) key.getPublic();
        String noKid = "{\"keys\":[{\"kty\":\"RSA\",\"n\":\"%s\",\"e\":\"%s\"}]}"
                .formatted(b64(pub.getModulus()), b64(pub.getPublicExponent()));
        server.expect(requestTo(URI)).andRespond(withSuccess(noKid, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> parse(rs256Token("k1"))).isInstanceOf(JwksKeyCache.UnavailableException.class);
    }

    private String parse(String token) {
        return Jwts.parser().keyLocator(cache.locator()).clock(() -> Date.from(clock.instant())).build()
                .parseSignedClaims(token).getPayload().getSubject();
    }

    private static String rs256Token(String kid) {
        return Jwts.builder().header().keyId(kid).and().subject("sub")
                .expiration(Date.from(NOW.plus(Duration.ofDays(3))))
                .signWith(key.getPrivate(), Jwts.SIG.RS256).compact();
    }

    private static String otherToken(String kid) {
        return Jwts.builder().header().keyId(kid).and().subject("sub")
                .expiration(Date.from(NOW.plus(Duration.ofDays(3))))
                .signWith(otherKey.getPrivate(), Jwts.SIG.RS256).compact();
    }

    private static String jwk(String kid, KeyPair pair) {
        RSAPublicKey pub = (RSAPublicKey) pair.getPublic();
        return "{\"kty\":\"RSA\",\"kid\":\"%s\",\"use\":\"sig\",\"alg\":\"RS256\",\"n\":\"%s\",\"e\":\"%s\"}"
                .formatted(kid, b64(pub.getModulus()), b64(pub.getPublicExponent()));
    }

    private static String jwks(String kid) {
        return "{\"keys\":[" + jwk(kid, key) + "]}";
    }

    /** k1 은 기본 키, 두 번째 kid 는 다른 키(키 교체). */
    private static String jwksWithOther(String kid1, String kid2) {
        return "{\"keys\":[" + jwk(kid1, key) + "," + jwk(kid2, otherKey) + "]}";
    }

    private static String b64(BigInteger value) {
        byte[] bytes = value.toByteArray();
        if (bytes.length > 1 && bytes[0] == 0) {
            bytes = Arrays.copyOfRange(bytes, 1, bytes.length);
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
