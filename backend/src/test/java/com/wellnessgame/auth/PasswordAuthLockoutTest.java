package com.wellnessgame.auth;

import com.wellnessgame.error.BadRequestException;
import com.wellnessgame.support.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * #55 로그인 브루트포스·계정 열거 방어: 더미 해시 비교, (아이디, IP) 잠금과 아이디 전체 한도, 잠금 해제,
 * 성공 시 초기화, 동시 요청 경합, 메모리 상한.
 */
class PasswordAuthLockoutTest {
    private static final Instant NOW = Instant.parse("2026-10-04T00:00:00Z");
    private static final String INVALID = "아이디 또는 비밀번호가 올바르지 않습니다.";
    private static final String IP = "198.51.100.1";

    private final MutableClock clock = new MutableClock(NOW);
    private Map<String, UserCredential> store;
    private UserCredentialRepository repository;
    private PasswordEncoder passwordEncoder;
    private PasswordAuthService service;

    @BeforeEach
    void setUp() {
        store = new HashMap<>();
        repository = mock(UserCredentialRepository.class);
        when(repository.existsByUsername(anyString())).thenAnswer(inv -> store.containsKey(inv.getArgument(0)));
        when(repository.findByUsername(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(store.get(inv.getArgument(0))));
        when(repository.save(any(UserCredential.class))).thenAnswer(inv -> {
            UserCredential credential = inv.getArgument(0);
            store.put(credential.getUsername(), credential);
            return credential;
        });
        // cost 4: 테스트 속도용. 동작은 기본 cost 와 같다.
        passwordEncoder = spy(new BCryptPasswordEncoder(4));
        service = newService(new LoginLockoutProperties(5, 50, 15), passwordEncoder);
        service.signUp("real_user", "password123", null);
        clearInvocations(passwordEncoder);
    }

    @Test
    void unknownUsernameStillRunsOneBcryptComparisonAndFailsLikeWrongPassword() {
        Throwable unknown = catchFailure("ghost_user", "password123", IP);
        verify(passwordEncoder, times(1)).matches(eq("password123"), anyString());

        Throwable wrongPassword = catchFailure("real_user", "wrong-password", IP);

        assertThat(unknown).isExactlyInstanceOf(BadRequestException.class).hasMessage(INVALID);
        assertThat(wrongPassword).isExactlyInstanceOf(BadRequestException.class).hasMessage(INVALID);
    }

    @Test
    void locksUsernameAndIpAfterFiveFailuresAndRejectsCorrectPassword() {
        failTimes("real_user", IP, 4);
        assertThat(service.signIn("real_user", "password123", IP).displayName()).isEqualTo("real_user");

        failTimes("real_user", IP, 5);
        clearInvocations(passwordEncoder);

        assertThatThrownBy(() -> service.signIn("real_user", "password123", IP))
                .isExactlyInstanceOf(BadRequestException.class)
                .hasMessage(INVALID);
        // 잠금 중에도 해시 비교 한 번을 거쳐 응답 시간이 비슷하다.
        verify(passwordEncoder, times(1)).matches(eq("password123"), anyString());
    }

    @Test
    void lockOnOneIpDoesNotLockOwnerOnAnotherIp() {
        failTimes("real_user", "203.0.113.66", 5);

        assertThatThrownBy(() -> service.signIn("real_user", "password123", "203.0.113.66"))
                .isInstanceOf(BadRequestException.class);
        assertThat(service.signIn("real_user", "password123", IP).displayName()).isEqualTo("real_user");
    }

    @Test
    void usernameWideLimitStopsDistributedAttack() {
        service = newService(new LoginLockoutProperties(5, 10, 15), passwordEncoder);
        for (int i = 0; i < 10; i++) {
            catchFailure("real_user", "wrong-password", "203.0.113." + i);
        }

        Throwable fromFreshIp = catchFailure("real_user", "password123", "192.0.2.200");
        Throwable unknownFromFreshIp = lockUnknownUsernameAcrossIps();

        assertThat(fromFreshIp).isExactlyInstanceOf(BadRequestException.class).hasMessage(INVALID);
        assertThat(unknownFromFreshIp).isExactlyInstanceOf(BadRequestException.class).hasMessage(INVALID);
        clock.setInstant(NOW.plus(Duration.ofMinutes(15)));
        assertThat(service.signIn("real_user", "password123", "192.0.2.200").displayName()).isEqualTo("real_user");
    }

    @Test
    void ownerSuccessDoesNotEraseOthersUsernameWideFailures() {
        service = newService(new LoginLockoutProperties(5, 6, 15), passwordEncoder);
        for (int i = 0; i < 5; i++) {
            catchFailure("real_user", "wrong-password", "203.0.113." + i);
        }
        service.signIn("real_user", "password123", IP);

        catchFailure("real_user", "wrong-password", "203.0.113.50");

        assertThatThrownBy(() -> service.signIn("real_user", "password123", IP)).isInstanceOf(BadRequestException.class);
    }

    @Test
    void unknownUsernameIsLockedTheSameWay() {
        failTimes("ghost_user", IP, 5);

        Throwable locked = catchFailure("ghost_user", "anything", IP);
        failTimes("real_user", IP, 5);
        Throwable lockedReal = catchFailure("real_user", "password123", IP);

        assertThat(locked).isExactlyInstanceOf(BadRequestException.class).hasMessage(INVALID);
        assertThat(lockedReal).isExactlyInstanceOf(BadRequestException.class).hasMessage(INVALID);
    }

    @Test
    void unlocksAfterLockoutPeriod() {
        failTimes("real_user", IP, 5);
        clock.setInstant(NOW.plus(Duration.ofMinutes(15)).minusSeconds(1));
        assertThatThrownBy(() -> service.signIn("real_user", "password123", IP)).isInstanceOf(BadRequestException.class);

        clock.setInstant(NOW.plus(Duration.ofMinutes(15)));

        assertThat(service.signIn("real_user", "password123", IP).displayName()).isEqualTo("real_user");
    }

    @Test
    void successResetsFailureCount() {
        failTimes("real_user", IP, 4);
        service.signIn("real_user", "password123", IP);

        failTimes("real_user", IP, 4);

        assertThat(service.signIn("real_user", "password123", IP).displayName()).isEqualTo("real_user");
    }

    @Test
    void lockIgnoresUsernameCaseAndSurroundingSpaces() {
        failTimes("Real_User ", IP, 5);

        assertThatThrownBy(() -> service.signIn("real_user", "password123", IP)).isInstanceOf(BadRequestException.class);
    }

    @Test
    void failuresOlderThanLockoutWindowAreForgotten() {
        failTimes("real_user", IP, 4);
        clock.setInstant(NOW.plus(Duration.ofMinutes(15)));

        failTimes("real_user", IP, 1);

        assertThat(service.signIn("real_user", "password123", IP).displayName()).isEqualTo("real_user");
    }

    @Test
    void concurrentFailuresCannotExceedLimit() throws Exception {
        String storedHash = store.get("real_user").getPasswordHash();
        AtomicInteger realHashComparisons = new AtomicInteger();
        PasswordEncoder counting = new BCryptPasswordEncoder(4) {
            @Override
            public boolean matches(CharSequence rawPassword, String encodedPassword) {
                if (storedHash.equals(encodedPassword)) {
                    realHashComparisons.incrementAndGet();
                }
                return super.matches(rawPassword, encodedPassword);
            }
        };
        PasswordAuthService concurrent = newService(new LoginLockoutProperties(5, 50, 15), counting);
        int threads = 24;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    try {
                        concurrent.signIn("real_user", "wrong-password", IP);
                    } catch (BadRequestException expected) {
                        // 모든 시도가 같은 400 으로 실패한다.
                    }
                    return null;
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<?> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(realHashComparisons.get()).isEqualTo(5);
        assertThatThrownBy(() -> concurrent.signIn("real_user", "password123", IP)).isInstanceOf(BadRequestException.class);
    }

    @Test
    void disabledWhenLimitsNotPositive() {
        PasswordAuthService unlimited = newService(new LoginLockoutProperties(0, 0, 15), passwordEncoder);
        for (int i = 0; i < 10; i++) {
            assertThatThrownBy(() -> unlimited.signIn("real_user", "wrong", IP)).isInstanceOf(BadRequestException.class);
        }

        assertThat(unlimited.signIn("real_user", "password123", IP).displayName()).isEqualTo("real_user");
    }

    @Test
    void guardPurgesExpiredEntries() {
        LoginAttemptGuard guard = new LoginAttemptGuard(new LoginLockoutProperties(5, 50, 15), clock, 100);
        guard.tryReserve("a", IP);
        for (int i = 0; i < 5; i++) {
            guard.tryReserve("b", IP);
        }
        assertThat(guard.trackedKeys()).isEqualTo(4); // (a, IP), (b, IP), a, b

        clock.setInstant(NOW.plus(Duration.ofMinutes(15)));
        guard.tryReserve("c", IP);

        assertThat(guard.trackedKeys()).isEqualTo(2);
    }

    @Test
    void guardEvictsOldestEntriesWhenFull() {
        LoginAttemptGuard guard = new LoginAttemptGuard(new LoginLockoutProperties(5, 50, 15), clock, 3);
        for (int i = 0; i < 3; i++) {
            guard.tryReserve("user" + i, IP);
            clock.setInstant(clock.instant().plusSeconds(1));
        }

        guard.tryReserve("newcomer", IP);

        assertThat(guard.trackedKeys()).isEqualTo(6); // 상한 3 × 저장소 2개
    }

    @Test
    void guardTruncatesOverlongUsernames() {
        LoginAttemptGuard guard = new LoginAttemptGuard(new LoginLockoutProperties(5, 50, 15), clock, 100);
        guard.tryReserve("x".repeat(10_000), IP);
        guard.tryReserve("x".repeat(20_000), IP);

        assertThat(guard.trackedKeys()).isEqualTo(2);
    }

    private Throwable lockUnknownUsernameAcrossIps() {
        for (int i = 0; i < 10; i++) {
            catchFailure("ghost_user", "wrong-password", "203.0.113." + (100 + i));
        }
        return catchFailure("ghost_user", "anything", "192.0.2.201");
    }

    private PasswordAuthService newService(LoginLockoutProperties properties, PasswordEncoder encoder) {
        return new PasswordAuthService(repository, encoder, properties, clock, 100_000);
    }

    private void failTimes(String username, String ip, int times) {
        for (int i = 0; i < times; i++) {
            catchFailure(username, "wrong-password", ip);
        }
    }

    private Throwable catchFailure(String username, String password, String ip) {
        try {
            service.signIn(username, password, ip);
        } catch (RuntimeException e) {
            return e;
        }
        throw new AssertionError("로그인이 실패해야 합니다: " + username);
    }
}
