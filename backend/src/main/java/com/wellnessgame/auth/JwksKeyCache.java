package com.wellnessgame.auth;

import io.jsonwebtoken.JwsHeader;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.LocatorAdapter;
import io.jsonwebtoken.security.Jwk;
import io.jsonwebtoken.security.JwkSet;
import io.jsonwebtoken.security.Jwks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.security.Key;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 외부 ID 토큰(Apple identity token, Google ID token) 서명 검증용 RS256 공개키(JWKS) 캐시.
 * {@link #locator()} 를 jjwt 파서의 keyLocator 로 쓴다.
 *
 * <ul>
 *   <li>서명: 헤더 alg 가 RS256 이 아니거나 kid 가 없으면 거부한다({@link JwtException}).</li>
 *   <li>메모리에 cacheTtl 동안 캐시하고 만료되면 다시 받는다.</li>
 *   <li>모르는 kid(키 교체)면 다시 받되, 위조 kid 로 공급자를 반복 호출하지 않도록
 *       {@link #MIN_REFRESH_INTERVAL} 안에서는 재조회하지 않는다.</li>
 *   <li>장애 격리(stale-on-error): 받기에 실패하면 {@link #FAILURE_BACKOFF} 동안 재시도하지 않고
 *       TTL 이 지난 기존 키를 계속 쓴다. 키가 하나도 없을 때만 {@link UnavailableException} 이다.</li>
 *   <li>재조회는 한 스레드만 한다. 기존 키가 있으면 다른 요청은 기다리지 않고 기존 키로 검증한다
 *       (키가 전혀 없는 최초 조회 때만 대기).</li>
 * </ul>
 * HTTP 타임아웃은 주입받은 {@link RestClient} 에 적용된 spring.http.client.connect-timeout / read-timeout 을 따른다.
 */
final class JwksKeyCache {
    private static final Logger log = LoggerFactory.getLogger(JwksKeyCache.class);
    static final Duration MIN_REFRESH_INTERVAL = Duration.ofMinutes(1);
    static final Duration FAILURE_BACKOFF = Duration.ofSeconds(30);
    private static final String RS256 = "RS256";

    private final String name;
    private final RestClient restClient;
    private final String jwksUri;
    private final Duration cacheTtl;
    private final Clock clock;

    private final ReentrantLock refreshLock = new ReentrantLock();
    private volatile CachedKeys cachedKeys;
    private volatile Instant retryNotBefore = Instant.MIN;

    /**
     * @param name 로그에 남길 공급자 이름(예: "Apple")
     */
    JwksKeyCache(String name, RestClient restClient, String jwksUri, Duration cacheTtl, Clock clock) {
        this.name = name;
        this.restClient = restClient;
        this.jwksUri = jwksUri;
        this.cacheTtl = cacheTtl;
        this.clock = clock;
    }

    LocatorAdapter<Key> locator() {
        return new LocatorAdapter<>() {
            @Override
            protected Key locate(JwsHeader header) {
                return publicKey(header);
            }
        };
    }

    private Key publicKey(JwsHeader header) {
        if (!RS256.equals(header.getAlgorithm())) {
            throw new JwtException("Unsupported " + name + " token algorithm");
        }
        String kid = header.getKeyId();
        if (kid == null || kid.isBlank()) {
            throw new JwtException("Missing " + name + " key id");
        }
        Key key = currentKeys().get(kid);
        if (key == null) {
            key = refreshForUnknownKid().get(kid);
        }
        if (key == null) {
            throw new JwtException("Unknown " + name + " key id");
        }
        return key;
    }

    private Map<String, RSAPublicKey> currentKeys() {
        CachedKeys cached = cachedKeys;
        if (cached != null && clock.instant().isBefore(cached.fetchedAt().plus(cacheTtl))) {
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
     * 기존 키가 없으면 실패 시 {@link UnavailableException}.
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
            } catch (UnavailableException e) {
                retryNotBefore = clock.instant().plus(FAILURE_BACKOFF);
                log.warn("{} JWKS 조회 실패. {} 동안 재시도하지 않고 기존 키({})를 사용합니다.",
                        name, FAILURE_BACKOFF, stale == null ? "없음" : stale.keys().size() + "개", e.getCause());
                return staleOrUnavailable(stale, e);
            }
        } finally {
            refreshLock.unlock();
        }
    }

    private boolean inFailureBackoff() {
        return clock.instant().isBefore(retryNotBefore);
    }

    private Map<String, RSAPublicKey> staleOrUnavailable(CachedKeys stale, UnavailableException failure) {
        if (stale != null) {
            return stale.keys();
        }
        throw failure != null ? failure : new UnavailableException(new IllegalStateException(name + " JWKS 재시도 대기 중"));
    }

    /** 호출자가 refreshLock 을 잡고 있어야 한다. 캐시는 호출자가 갱신한다. */
    private CachedKeys fetchKeys() {
        String body;
        try {
            body = restClient.get().uri(jwksUri).retrieve().body(String.class);
        } catch (RestClientException e) {
            throw new UnavailableException(e);
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
            throw new UnavailableException(e);
        }
        if (keys.isEmpty()) {
            throw new UnavailableException(new IllegalStateException(name + " JWKS 에 RSA 키가 없습니다."));
        }
        return new CachedKeys(Map.copyOf(keys), clock.instant());
    }

    private record CachedKeys(Map<String, RSAPublicKey> keys, Instant fetchedAt) {
    }

    /** JWKS 를 받지 못함(호출자가 503 으로 바꾼다). jjwt 예외 계층 밖이라 파서가 감싸지 않고 그대로 전파된다. */
    static final class UnavailableException extends RuntimeException {
        UnavailableException(Throwable cause) {
            super(cause);
        }
    }
}
