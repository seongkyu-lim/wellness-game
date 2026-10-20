package com.wellnessgame.auth;

import com.wellnessgame.support.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** #103 TokenValidityService 캐시: 무효화 커밋 전에 읽은 이전 값이 늦게 캐시에 써져도 새 기준을 덮지 않는다. */
class TokenValidityCacheTest {
    private static final String USER = "cache-user";
    private static final Instant NOW = Instant.parse("2026-06-17T10:00:00Z");

    private final AtomicReference<UserAuthState> row = new AtomicReference<>();
    private UserAuthStateRepository repository;
    private MutableClock clock;
    private TokenValidityService service;

    @BeforeEach
    void setUp() {
        repository = mock(UserAuthStateRepository.class);
        clock = new MutableClock(NOW);
        service = new TokenValidityService(repository, clock, 1000);
        when(repository.insertIfAbsent(eq(USER), any())).thenAnswer(inv -> {
            row.compareAndSet(null, state(inv.getArgument(1), null));
            return 1;
        });
        when(repository.advanceTo(eq(USER), any())).thenAnswer(inv -> {
            UserAuthState current = row.get();
            row.set(state(later(current.getTokensValidAfter(), inv.getArgument(1)), current.getReadTokensValidAfter()));
            return 1;
        });
        when(repository.advanceReadTo(eq(USER), any())).thenAnswer(inv -> {
            UserAuthState current = row.get();
            row.set(state(current.getTokensValidAfter(), later(current.getReadTokensValidAfter(), inv.getArgument(1))));
            return 1;
        });
    }

    @Test
    void staleEmptyReadDoesNotOverwriteCutoffCachedByConcurrentRevoke() {
        AtomicBoolean firstRead = new AtomicBoolean(true);
        when(repository.findById(anyString())).thenAnswer(inv -> {
            if (firstRead.getAndSet(false)) {
                // 이 요청이 DB 를 읽은 직후(값 없음), 캐시에 쓰기 전에 다른 요청의 모든 기기 로그아웃이 끝난다.
                service.revokeIssuedTokens(USER);
                return Optional.empty();
            }
            return Optional.ofNullable(row.get());
        });

        assertThat(service.isRevoked(USER, NOW.minusSeconds(1))).isTrue();
        assertThat(service.isRevoked(USER, NOW.minusSeconds(1))).isTrue(); // 캐시에 남은 값도 새 기준
    }

    @Test
    void staleOlderReadCutoffDoesNotOverwriteNewerOne() {
        service.revokeReadTokens(USER);
        clock.setInstant(NOW.plusSeconds(5));
        UserAuthState stale = row.get();
        AtomicBoolean firstRead = new AtomicBoolean(true);
        when(repository.findById(anyString())).thenAnswer(inv -> {
            if (firstRead.getAndSet(false)) {
                service.revokeReadTokens(USER);
                return Optional.of(stale);
            }
            return Optional.ofNullable(row.get());
        });
        // 캐시가 만료돼 DB 를 다시 읽게 한다.
        clock.setInstant(NOW.plus(TokenValidityService.CACHE_TTL).plusSeconds(10));

        assertThat(service.isRevoked(USER, TokenScope.READ, NOW.plusSeconds(4))).isTrue();
    }

    @Test
    void readCutoffAppliesOnlyToReadScopeAndRowCreatedByIt() {
        when(repository.findById(anyString())).thenAnswer(inv -> Optional.ofNullable(row.get()));
        service.revokeReadTokens(USER);

        assertThat(row.get().getTokensValidAfter()).isEqualTo(TokenValidityService.NO_CUTOFF);
        assertThat(service.isRevoked(USER, TokenScope.READ, NOW.minusSeconds(1))).isTrue();
        assertThat(service.isRevoked(USER, TokenScope.READ, NOW)).isFalse();
        assertThat(service.isRevoked(USER, TokenScope.FULL, NOW.minusSeconds(1))).isFalse();
        // NO_CUTOFF 는 "기준 없음"이라 iat 가 없는 전체 권한 토큰도 막지 않는다.
        assertThat(service.isRevoked(USER, TokenScope.FULL, null)).isFalse();

        // 전체 기준은 조회 토큰에도 적용된다(두 기준 중 늦은 쪽).
        clock.setInstant(NOW.plusSeconds(60));
        service.revokeIssuedTokens(USER);
        assertThat(service.isRevoked(USER, TokenScope.READ, NOW.plusSeconds(30))).isTrue();
        assertThat(service.isRevoked(USER, TokenScope.FULL, NOW.plusSeconds(30))).isTrue();
    }

    private static UserAuthState state(Instant all, Instant read) {
        return new UserAuthState(USER, all, read);
    }

    private static Instant later(Instant a, Instant b) {
        if (a == null) {
            return b;
        }
        return b == null || a.isAfter(b) ? a : b;
    }
}
