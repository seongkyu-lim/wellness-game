package com.wellnessgame.auth;

import com.wellnessgame.error.ServiceUnavailableException;
import com.wellnessgame.i18n.Messages;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.IncorrectClaimException;
import io.jsonwebtoken.JwsHeader;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.LocatorAdapter;
import io.jsonwebtoken.MissingClaimException;
import io.jsonwebtoken.security.Jwk;
import io.jsonwebtoken.security.JwkSet;
import io.jsonwebtoken.security.Jwks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.security.Key;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * iOS Sign in with Apple 의 identity token(JWT)을 Apple JWKS 로 직접 검증해 "apple:{sub}" userId 를 만든다.
 *
 * <ul>
 *   <li>서명: RS256 만 허용하고 헤더 kid 로 JWKS 의 공개키를 고른다.</li>
 *   <li>클레임: iss = https://appleid.apple.com, aud = oauth.apple.bundle-id, exp 필수·미래.</li>
 *   <li>식별: sub 만 쓴다. 이메일(릴레이 주소일 수 있고 바뀔 수 있음)은 식별에 쓰지 않는다.</li>
 * </ul>
 *
 * <p>JWKS 캐시 정책
 * <ul>
 *   <li>메모리에 {@link AppleNativeProperties#jwksCacheTtl()} 동안 캐시하고 만료되면 다시 받는다.</li>
 *   <li>모르는 kid(키 교체)면 다시 받되, 위조 kid 로 Apple 을 반복 호출하지 않도록
 *       {@link #MIN_REFRESH_INTERVAL} 안에서는 재조회하지 않는다.</li>
 *   <li>장애 격리(stale-on-error): 받기에 실패하면 {@link #FAILURE_BACKOFF} 동안 재시도하지 않고
 *       TTL 이 지난 기존 키를 계속 쓴다. 키가 하나도 없을 때만 503 이다.</li>
 *   <li>재조회는 한 스레드만 한다. 기존 키가 있으면 다른 요청은 기다리지 않고 기존 키로 검증한다
 *       (키가 전혀 없는 최초 조회 때만 대기).</li>
 * </ul>
 * HTTP 타임아웃은 주입받은 {@link RestClient.Builder} 에 적용된
 * spring.http.client.connect-timeout / read-timeout 을 따른다.
 */
@Service
public class AppleNativeAuthService {
    private static final Logger log = LoggerFactory.getLogger(AppleNativeAuthService.class);
    static final String APPLE_ISSUER = "https://appleid.apple.com";
    static final Duration MIN_REFRESH_INTERVAL = Duration.ofMinutes(1);
    static final Duration FAILURE_BACKOFF = Duration.ofSeconds(30);
    static final int MAX_FULL_NAME_LENGTH = 100;
    private static final String RS256 = "RS256";

    private final RestClient restClient;
    private final AppleNativeProperties properties;
    private final Clock clock;
    private final JwtParser parser;

    private final ReentrantLock refreshLock = new ReentrantLock();
    private volatile CachedKeys cachedKeys;
    private volatile Instant retryNotBefore = Instant.MIN;

    public AppleNativeAuthService(RestClient.Builder restClientBuilder, AppleNativeProperties properties, Clock clock) {
        this.restClient = restClientBuilder.build();
        this.properties = properties;
        this.clock = clock;
        this.parser = properties.configured() ? buildParser() : null;
    }

    public SocialLoginResult authenticate(String identityToken, String fullName) {
        if (parser == null) {
            throw NativeAuthSupport.unavailable(log, "애플", "OAUTH_APPLE_BUNDLE_ID 환경변수", "auth.apple.unavailable");
        }
        NativeAuthSupport.requireToken(identityToken, "auth.apple.identity-token-blank");

        Claims claims = verify(identityToken.trim());
        String sub = claims.getSubject();
        if (sub == null || sub.isBlank() || claims.getExpiration() == null) {
            throw new UnauthorizedException(Messages.get("auth.apple.identity-token-invalid"));
        }
        return new SocialLoginResult("apple:" + sub, displayName(fullName));
    }

    private Claims verify(String identityToken) {
        try {
            return parser.parseSignedClaims(identityToken).getPayload();
        } catch (JwksUnavailableException e) {
            throw new ServiceUnavailableException(Messages.get("auth.apple.verification-failed"), e.getCause());
        } catch (ExpiredJwtException e) {
            throw new UnauthorizedException(Messages.get("auth.apple.identity-token-expired"), e);
        } catch (IncorrectClaimException | MissingClaimException e) {
            String claim = e.getClaimName();
            String code = Claims.AUDIENCE.equals(claim) ? "auth.apple.wrong-audience"
                    : Claims.ISSUER.equals(claim) ? "auth.apple.wrong-issuer"
                    : "auth.apple.identity-token-invalid";
            throw new UnauthorizedException(Messages.get(code), e);
        } catch (JwtException | IllegalArgumentException e) {
            // 서명 불일치, 형식 오류, 지원하지 않는 alg, 모르는 kid 등
            throw new UnauthorizedException(Messages.get("auth.apple.identity-token-invalid"), e);
        }
    }

    private JwtParser buildParser() {
        return Jwts.parser()
                .keyLocator(new LocatorAdapter<Key>() {
                    @Override
                    protected Key locate(JwsHeader header) {
                        return publicKey(header);
                    }
                })
                .requireIssuer(APPLE_ISSUER)
                .requireAudience(properties.bundleId())
                .clock(() -> Date.from(clock.instant()))
                .build();
    }

    private Key publicKey(JwsHeader header) {
        if (!RS256.equals(header.getAlgorithm())) {
            throw new UnsupportedAlgorithmException();
        }
        String kid = header.getKeyId();
        if (kid == null || kid.isBlank()) {
            throw new UnknownKeyException();
        }
        Key key = currentKeys().get(kid);
        if (key == null) {
            key = refreshForUnknownKid().get(kid);
        }
        if (key == null) {
            throw new UnknownKeyException();
        }
        return key;
    }

    private Map<String, RSAPublicKey> currentKeys() {
        CachedKeys cached = cachedKeys;
        if (cached != null && clock.instant().isBefore(cached.fetchedAt().plus(properties.jwksCacheTtl()))) {
            return cached.keys();
        }
        return refresh(cached);
    }

    private Map<String, RSAPublicKey> refreshForUnknownKid() {
        CachedKeys cached = cachedKeys;
        if (cached != null && clock.instant().isBefore(cached.fetchedAt().plus(MIN_REFRESH_INTERVAL))) {
            return cached.keys();
        }
        return refresh(cached);
    }

    /**
     * JWKS 를 다시 받는다. 실패하거나 다른 스레드가 받는 중이면 기존 키(stale)를 돌려준다.
     * 기존 키가 없으면 실패 시 {@link JwksUnavailableException}.
     */
    private Map<String, RSAPublicKey> refresh(CachedKeys stale) {
        if (inFailureBackoff()) {
            return staleOrUnavailable(stale, null);
        }
        if (stale == null) {
            refreshLock.lock();
        } else if (!refreshLock.tryLock()) {
            return stale.keys();
        }
        try {
            CachedKeys latest = cachedKeys;
            if (latest != stale) {
                return latest.keys(); // 기다리는 동안 다른 스레드가 받았다
            }
            if (inFailureBackoff()) {
                return staleOrUnavailable(stale, null);
            }
            try {
                CachedKeys fetched = fetchKeys();
                cachedKeys = fetched;
                return fetched.keys();
            } catch (JwksUnavailableException e) {
                retryNotBefore = clock.instant().plus(FAILURE_BACKOFF);
                log.warn("Apple JWKS 조회 실패. {} 동안 재시도하지 않고 기존 키({})를 사용합니다.",
                        FAILURE_BACKOFF, stale == null ? "없음" : stale.keys().size() + "개", e.getCause());
                return staleOrUnavailable(stale, e);
            }
        } finally {
            refreshLock.unlock();
        }
    }

    private boolean inFailureBackoff() {
        return clock.instant().isBefore(retryNotBefore);
    }

    private static Map<String, RSAPublicKey> staleOrUnavailable(CachedKeys stale, JwksUnavailableException failure) {
        if (stale != null) {
            return stale.keys();
        }
        throw failure != null ? failure : new JwksUnavailableException(new IllegalStateException("Apple JWKS 재시도 대기 중"));
    }

    /** 호출자가 refreshLock 을 잡고 있어야 한다. 캐시는 호출자가 갱신한다. */
    private CachedKeys fetchKeys() {
        String body;
        try {
            body = restClient.get().uri(properties.jwksUri()).retrieve().body(String.class);
        } catch (RestClientException e) {
            throw new JwksUnavailableException(e);
        }
        Map<String, RSAPublicKey> keys = new HashMap<>();
        try {
            JwkSet jwkSet = Jwks.setParser().build().parse(body == null ? "" : body);
            for (Jwk<?> jwk : jwkSet.getKeys()) {
                if (jwk.getId() != null && jwk.toKey() instanceof RSAPublicKey rsa) {
                    keys.put(jwk.getId(), rsa);
                }
            }
        } catch (JwtException | IllegalArgumentException e) {
            throw new JwksUnavailableException(e);
        }
        if (keys.isEmpty()) {
            throw new JwksUnavailableException(new IllegalStateException("Apple JWKS 에 RSA 키가 없습니다."));
        }
        return new CachedKeys(Map.copyOf(keys), clock.instant());
    }

    private static String displayName(String fullName) {
        if (fullName == null || fullName.isBlank()) {
            // Apple 은 이름을 최초 로그인 때만 주므로 없을 때 기본 이름을 쓴다.
            return Messages.get("auth.apple.default-display-name");
        }
        String trimmed = fullName.trim();
        if (trimmed.codePointCount(0, trimmed.length()) <= MAX_FULL_NAME_LENGTH) {
            return trimmed;
        }
        // 거부 대신 잘라 쓴다. 서로게이트 쌍(이모지 등)이 깨지지 않게 코드 포인트 기준으로 자른다.
        return trimmed.substring(0, trimmed.offsetByCodePoints(0, MAX_FULL_NAME_LENGTH));
    }

    private record CachedKeys(Map<String, RSAPublicKey> keys, Instant fetchedAt) {
    }

    /** JWKS 를 받지 못함(503). jjwt 예외 계층 밖이라 파서가 감싸지 않고 그대로 전파된다. */
    private static final class JwksUnavailableException extends RuntimeException {
        JwksUnavailableException(Throwable cause) {
            super(cause);
        }
    }

    private static final class UnknownKeyException extends JwtException {
        UnknownKeyException() {
            super("Unknown Apple key id");
        }
    }

    private static final class UnsupportedAlgorithmException extends JwtException {
        UnsupportedAlgorithmException() {
            super("Unsupported Apple identity token algorithm");
        }
    }
}
