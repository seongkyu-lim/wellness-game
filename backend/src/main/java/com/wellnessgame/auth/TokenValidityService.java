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
 * 사용자별 액세스 토큰 무효화 기준.
 *
 * <ul>
 *   <li>tokens_valid_after: 모든 범위. 모든 기기 로그아웃과 리프레시 토큰 재사용 감지 때 지금 시각으로 옮긴다.
 *       iat 가 기준보다 이전인 토큰(scope 무관, 이전 형식 포함)을 거부한다.</li>
 *   <li>read_tokens_valid_after: 조회 전용(scope=read) 토큰만. 이 기기 로그아웃 때 옮긴다(#103). 조회 토큰에는 두 기준 중
 *       더 늦은 쪽을 적용한다.</li>
 *   <li>JWT iat 는 초 단위라서 기준도 초 단위로 내린다. 같은 초에 발급된 토큰은 통과한다(직후 재로그인 토큰이 막히지 않게).</li>
 *   <li>요청마다 DB 를 읽지 않도록 사용자별 값(없음 포함)을 {@link #CACHE_TTL} 동안 메모리에 둔다. 기준을 바꾼 인스턴스는 즉시 반영하고,
 *       다른 인스턴스는 최대 {@link #CACHE_TTL} 늦게 반영한다.</li>
 *   <li>기준은 앞으로만 움직이므로 캐시를 고칠 때 아직 유효한 기존 값과 비교해 더 늦은 시각을 남긴다. 무효화 커밋 전에 DB 를 읽은
 *       요청이 늦게 캐시에 쓰더라도 방금 옮긴 기준을 이전 값(또는 없음)으로 덮지 않는다(#103).</li>
 * </ul>
 */
@Service
public class TokenValidityService {
    static final Duration CACHE_TTL = Duration.ofSeconds(30);
    /** tokens_valid_after 의 "기준 없음" 값. 조회 토큰만 무효로 만들 때 행을 새로 만들면 이 값을 넣는다. */
    static final Instant NO_CUTOFF = Instant.EPOCH;
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

    /** 전체 권한 토큰 기준으로 판단한다. iat 가 이 사용자의 기준보다 이전이면 true(거부). 기준이 없으면 false. */
    public boolean isRevoked(String userId, Instant issuedAt) {
        return isRevoked(userId, TokenScope.FULL, issuedAt);
    }

    /** iat 가 이 범위에 적용되는 기준보다 이전이면 true(거부). 조회 토큰은 두 기준 중 늦은 쪽을 쓴다. */
    public boolean isRevoked(String userId, TokenScope scope, Instant issuedAt) {
        CachedState state = state(userId);
        Instant cutoff = scope == TokenScope.READ ? later(state.validAfter(), state.readValidAfter()) : state.validAfter();
        return cutoff != null && (issuedAt == null || issuedAt.isBefore(cutoff));
    }

    /** 지금까지 발급된 이 사용자의 액세스 토큰을 모두 무효로 만든다. 호출자의 트랜잭션에 참여한다. */
    @Transactional
    public void revokeIssuedTokens(String userId) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        // 원자적 upsert: 같은 사용자의 첫 revoke 가 동시에 와도 PK 위반 없이 둘 다 성공한다.
        repository.insertIfAbsent(userId, now);
        repository.advanceTo(userId, now);
        cacheFromDatabase(userId, now, null);
    }

    /** 지금까지 발급된 이 사용자의 조회 전용(scope=read) 액세스 토큰만 무효로 만든다(#103). 호출자의 트랜잭션에 참여한다. */
    @Transactional
    public void revokeReadTokens(String userId) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        // 행이 없으면 전체 기준은 "없음"을 뜻하는 NO_CUTOFF 로 만든다(tokens_valid_after 는 not null).
        repository.insertIfAbsent(userId, NO_CUTOFF);
        repository.advanceReadTo(userId, now);
        cacheFromDatabase(userId, null, now);
    }

    /**
     * 지우지 않고 새 값을 넣는다: 커밋 전에 다른 요청이 DB 의 이전 값을 읽어 캐시하는 일을 막는다.
     * 트랜잭션 안의 읽기라 방금 쓴 값을 본다. 행이 안 보이면(있을 수 없지만) 방금 옮긴 값을 쓴다.
     */
    private void cacheFromDatabase(String userId, Instant advancedAll, Instant advancedRead) {
        CachedState loaded = repository.findById(userId)
                .map(state -> new CachedState(allCutoff(state), state.getReadTokensValidAfter(), 0))
                .orElse(new CachedState(advancedAll, advancedRead, 0));
        merge(userId, loaded.validAfter(), loaded.readValidAfter());
    }

    private CachedState state(String userId) {
        CachedState cached = cache.get(userId);
        if (cached != null && clock.millis() < cached.expiresAtMillis()) {
            return cached;
        }
        UserAuthState loaded = repository.findById(userId).orElse(null);
        return merge(userId,
                loaded == null ? null : allCutoff(loaded),
                loaded == null ? null : loaded.getReadTokensValidAfter());
    }

    /** 아직 유효한 기존 캐시 값과 비교해 필드별로 더 늦은 시각을 남긴다. 만료된 기존 값은 버린다. */
    private CachedState merge(String userId, Instant validAfter, Instant readValidAfter) {
        long nowMillis = clock.millis();
        long expiresAt = nowMillis + CACHE_TTL.toMillis();
        return cache.compute(userId, (key, existing) -> {
            if (existing == null || nowMillis >= existing.expiresAtMillis()) {
                return new CachedState(validAfter, readValidAfter, expiresAt);
            }
            return new CachedState(later(existing.validAfter(), validAfter),
                    later(existing.readValidAfter(), readValidAfter), expiresAt);
        });
    }

    private static Instant allCutoff(UserAuthState state) {
        Instant value = state.getTokensValidAfter();
        return NO_CUTOFF.equals(value) ? null : value;
    }

    private static Instant later(Instant a, Instant b) {
        if (a == null) {
            return b;
        }
        return b == null || a.isAfter(b) ? a : b;
    }

    private record CachedState(Instant validAfter, Instant readValidAfter, long expiresAtMillis) {
    }
}
