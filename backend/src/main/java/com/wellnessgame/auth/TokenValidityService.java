package com.wellnessgame.auth;

import com.wellnessgame.common.BoundedExpiringStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
 *   <li>read_tokens_valid_after: 조회 전용(scope=read) 토큰만. 앱(전체 권한) family 의 이 기기 로그아웃 때 옮긴다(#103).
 *       조회 토큰에는 두 기준을 모두 적용한다.</li>
 *   <li>JWT iat 는 초 단위라서 기준도 초 단위로 내린다. 전체 기준은 같은 초에 발급된 토큰을 통과시킨다(직후 재로그인 토큰이
 *       막히지 않게). 조회 기준은 같은 초의 토큰도 거부한다(로그아웃 직전 같은 초에 받은 Watch 토큰이 살아남지 않게).</li>
 *   <li>요청마다 DB 를 읽지 않도록 사용자별 값(없음 포함)을 {@link #CACHE_TTL} 동안 메모리에 둔다. 기준을 바꾼 인스턴스는 즉시 반영하고,
 *       다른 인스턴스는 최대 {@link #CACHE_TTL} 늦게 반영한다.</li>
 *   <li>기준은 앞으로만 움직이므로 캐시를 고칠 때 아직 유효한 기존 값과 비교해 더 늦은 시각을 남긴다. 무효화 커밋 전에 DB 를 읽은
 *       요청이 늦게 캐시에 쓰더라도 방금 옮긴 기준을 이전 값(또는 없음)으로 덮지 않는다(#103). 무효화한 값은 커밋 뒤에만 캐시에
 *       넣는다(롤백된 기준이 캐시에 남지 않게).</li>
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
        Instant all = state.validAfter();
        if (all != null && (issuedAt == null || issuedAt.isBefore(all))) {
            return true;
        }
        Instant read = scope == TokenScope.READ ? state.readValidAfter() : null;
        // 조회 기준은 경계 초도 거부한다(기준이 초 단위로 내려가 같은 초에 발급된 조회 토큰이 살아남지 않게).
        return read != null && (issuedAt == null || !issuedAt.isAfter(read));
    }

    /** 지금까지 발급된 이 사용자의 액세스 토큰을 모두 무효로 만든다. 호출자의 트랜잭션에 참여한다. */
    @Transactional
    public void revokeIssuedTokens(String userId) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        // 원자적 upsert: 같은 사용자의 첫 revoke 가 동시에 와도 PK 위반 없이 둘 다 성공한다.
        repository.insertIfAbsent(userId, now);
        repository.advanceTo(userId, now);
        cacheAfterCommit(userId, now, null);
    }

    /** 지금까지 발급된 이 사용자의 조회 전용(scope=read) 액세스 토큰만 무효로 만든다(#103). 호출자의 트랜잭션에 참여한다. */
    @Transactional
    public void revokeReadTokens(String userId) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        // 행이 없으면 전체 기준은 "없음"을 뜻하는 NO_CUTOFF 로 만든다(tokens_valid_after 는 not null).
        repository.insertIfAbsent(userId, NO_CUTOFF);
        repository.advanceReadTo(userId, now);
        cacheAfterCommit(userId, null, now);
    }

    /**
     * 방금 쓴 기준(트랜잭션 안의 읽기라 자기 쓰기를 본다)을 커밋된 뒤에만 캐시에 넣는다. 롤백되면 캐시는 그대로다.
     * 커밋 전에 다른 요청이 이전 값을 캐시해도 {@link #merge} 가 더 늦은 시각을 남기므로 커밋 뒤 이 값이 이긴다.
     * 트랜잭션 밖에서 불리면 바로 넣는다. 행이 안 보이면(있을 수 없지만) 방금 옮긴 값을 쓴다.
     */
    private void cacheAfterCommit(String userId, Instant advancedAll, Instant advancedRead) {
        Runnable update = repository.findById(userId)
                .<Runnable>map(state -> () -> merge(userId, allCutoff(state), state.getReadTokensValidAfter()))
                .orElse(() -> merge(userId, advancedAll, advancedRead));
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    update.run();
                }
            });
        } else {
            update.run();
        }
    }

    private CachedState state(String userId) {
        CachedState cached = cache.get(userId);
        if (cached != null && clock.millis() < cached.expiresAtMillis()) {
            return cached;
        }
        return repository.findById(userId)
                .map(state -> merge(userId, allCutoff(state), state.getReadTokensValidAfter()))
                .orElseGet(() -> merge(userId, null, null));
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
