package com.wellnessgame.auth;

import com.wellnessgame.common.BoundedExpiringStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * 사용자별 액세스 토큰 무효화 기준(tokens_valid_after). 모든 기기 로그아웃과 리프레시 토큰 재사용 감지 때 지금 시각으로
 * 옮기고, 인증 때 iat 가 기준보다 이전인 토큰(scope 무관, 이전 형식 포함)을 거부한다.
 *
 * <ul>
 *   <li>JWT iat 는 초 단위라서 기준도 초 단위로 내린다. 같은 초에 발급된 토큰은 통과한다(직후 재로그인 토큰이 막히지 않게).</li>
 *   <li>요청마다 DB 를 읽지 않도록 사용자별 값(없음 포함)을 {@link #CACHE_TTL} 동안 메모리에 둔다. 기준을 바꾼 인스턴스는 즉시 반영하고,
 *       다른 인스턴스는 최대 {@link #CACHE_TTL} 늦게 반영한다.</li>
 * </ul>
 */
@Service
public class TokenValidityService {
    static final Duration CACHE_TTL = Duration.ofSeconds(30);
    private static final long PURGE_INTERVAL_MILLIS = 60_000;

    private final UserAuthStateRepository repository;
    private final Clock clock;
    private final BoundedExpiringStore<CachedState> cache;

    public TokenValidityService(
            UserAuthStateRepository repository,
            Clock clock,
            @Value("${app.rate-limit.max-keys:100000}") int maxKeys
    ) {
        this.repository = repository;
        this.clock = clock;
        this.cache = new BoundedExpiringStore<>(clock, PURGE_INTERVAL_MILLIS, Math.max(1, maxKeys), CachedState::expiresAtMillis);
    }

    /** iat 가 이 사용자의 기준보다 이전이면 true(거부). 기준이 없으면 false. */
    public boolean isRevoked(String userId, Instant issuedAt) {
        Instant validAfter = validAfter(userId);
        return validAfter != null && (issuedAt == null || issuedAt.isBefore(validAfter));
    }

    /** 지금까지 발급된 이 사용자의 액세스 토큰을 모두 무효로 만든다. 호출자의 트랜잭션에 참여한다. */
    @Transactional
    public void revokeIssuedTokens(String userId) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        UserAuthState state = repository.findById(userId).orElse(null);
        if (state == null) {
            state = repository.save(new UserAuthState(userId, now));
        } else {
            state.advanceTo(now);
        }
        // 지우지 않고 새 값을 넣는다: 커밋 전에 다른 요청이 DB 의 이전 값을 읽어 캐시하는 일을 막는다.
        Instant validAfter = state.getTokensValidAfter();
        long expiresAt = clock.millis() + CACHE_TTL.toMillis();
        cache.compute(userId, (key, existing) -> new CachedState(validAfter, expiresAt));
    }

    private Instant validAfter(String userId) {
        long nowMillis = clock.millis();
        CachedState cached = cache.get(userId);
        if (cached != null && nowMillis < cached.expiresAtMillis()) {
            return cached.validAfter();
        }
        Instant loaded = repository.findById(userId).map(UserAuthState::getTokensValidAfter).orElse(null);
        cache.compute(userId, (key, existing) -> new CachedState(loaded, nowMillis + CACHE_TTL.toMillis()));
        return loaded;
    }

    private record CachedState(Instant validAfter, long expiresAtMillis) {
    }
}
