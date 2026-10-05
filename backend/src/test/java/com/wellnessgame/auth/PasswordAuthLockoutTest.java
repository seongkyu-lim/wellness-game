package com.wellnessgame.auth;

import com.wellnessgame.error.BadRequestException;
import com.wellnessgame.support.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

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
 * #55 로그인 브루트포스·계정 열거 방어: 더미 해시 비교, 연속 실패 잠금, 잠금 해제, 성공 시 초기화.
 */
class PasswordAuthLockoutTest {
    private static final Instant NOW = Instant.parse("2026-10-04T00:00:00Z");
    private static final String INVALID = "아이디 또는 비밀번호가 올바르지 않습니다.";

    private final MutableClock clock = new MutableClock(NOW);
    private Map<String, UserCredential> store;
    private PasswordEncoder passwordEncoder;
    private PasswordAuthService service;

    @BeforeEach
    void setUp() {
        store = new HashMap<>();
        UserCredentialRepository repository = mock(UserCredentialRepository.class);
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
        service = new PasswordAuthService(repository, passwordEncoder, new LoginLockoutProperties(5, 15), clock);
        service.signUp("real_user", "password123", null);
        clearInvocations(passwordEncoder);
    }

    @Test
    void unknownUsernameStillRunsOneBcryptComparisonAndFailsLikeWrongPassword() {
        Throwable unknown = catchFailure("ghost_user", "password123");
        verify(passwordEncoder, times(1)).matches(eq("password123"), anyString());

        Throwable wrongPassword = catchFailure("real_user", "wrong-password");

        assertThat(unknown).isExactlyInstanceOf(BadRequestException.class).hasMessage(INVALID);
        assertThat(wrongPassword).isExactlyInstanceOf(BadRequestException.class).hasMessage(INVALID);
    }

    @Test
    void locksAfterFiveConsecutiveFailuresAndRejectsCorrectPassword() {
        failTimes("real_user", 4);
        assertThat(service.signIn("real_user", "password123").displayName()).isEqualTo("real_user");

        failTimes("real_user", 5);
        clearInvocations(passwordEncoder);

        assertThatThrownBy(() -> service.signIn("real_user", "password123"))
                .isExactlyInstanceOf(BadRequestException.class)
                .hasMessage(INVALID);
        // 잠금 중에도 해시 비교 한 번을 거쳐 응답 시간이 비슷하다.
        verify(passwordEncoder, times(1)).matches(eq("password123"), anyString());
    }

    @Test
    void unknownUsernameIsLockedTheSameWay() {
        failTimes("ghost_user", 5);

        Throwable locked = catchFailure("ghost_user", "anything");
        Throwable lockedReal = lockAndCatch();

        assertThat(locked).isExactlyInstanceOf(BadRequestException.class).hasMessage(INVALID);
        assertThat(lockedReal).isExactlyInstanceOf(BadRequestException.class).hasMessage(INVALID);
    }

    @Test
    void unlocksAfterLockoutPeriod() {
        failTimes("real_user", 5);
        clock.setInstant(NOW.plus(Duration.ofMinutes(15)).minusSeconds(1));
        assertThatThrownBy(() -> service.signIn("real_user", "password123")).isInstanceOf(BadRequestException.class);

        clock.setInstant(NOW.plus(Duration.ofMinutes(15)));

        assertThat(service.signIn("real_user", "password123").displayName()).isEqualTo("real_user");
    }

    @Test
    void successResetsFailureCount() {
        failTimes("real_user", 4);
        service.signIn("real_user", "password123");

        failTimes("real_user", 4);

        assertThat(service.signIn("real_user", "password123").displayName()).isEqualTo("real_user");
    }

    @Test
    void lockIgnoresUsernameCaseAndSurroundingSpaces() {
        failTimes("Real_User ", 5);

        assertThatThrownBy(() -> service.signIn("real_user", "password123")).isInstanceOf(BadRequestException.class);
    }

    @Test
    void failuresOlderThanLockoutWindowAreForgotten() {
        failTimes("real_user", 4);
        clock.setInstant(NOW.plus(Duration.ofMinutes(15)));

        failTimes("real_user", 1);

        assertThat(service.signIn("real_user", "password123").displayName()).isEqualTo("real_user");
    }

    @Test
    void disabledWhenMaxFailedLoginsNotPositive() {
        PasswordAuthService unlimited = new PasswordAuthService(
                mockRepositoryWith(store), passwordEncoder, new LoginLockoutProperties(0, 15), clock);
        for (int i = 0; i < 10; i++) {
            assertThatThrownBy(() -> unlimited.signIn("real_user", "wrong")).isInstanceOf(BadRequestException.class);
        }

        assertThat(unlimited.signIn("real_user", "password123").displayName()).isEqualTo("real_user");
    }

    @Test
    void guardPurgesExpiredEntries() {
        LoginAttemptGuard guard = new LoginAttemptGuard(new LoginLockoutProperties(5, 15), clock);
        guard.recordFailure("a");
        for (int i = 0; i < 5; i++) {
            guard.recordFailure("b");
        }
        assertThat(guard.trackedKeys()).isEqualTo(2);

        clock.setInstant(NOW.plus(Duration.ofMinutes(15)));
        guard.isLocked("c");

        assertThat(guard.trackedKeys()).isZero();
    }

    @Test
    void guardTruncatesOverlongKeys() {
        LoginAttemptGuard guard = new LoginAttemptGuard(new LoginLockoutProperties(5, 15), clock);
        guard.recordFailure("x".repeat(10_000));
        guard.recordFailure("x".repeat(20_000));

        assertThat(guard.trackedKeys()).isEqualTo(1);
    }

    private Throwable lockAndCatch() {
        failTimes("real_user", 5);
        return catchFailure("real_user", "password123");
    }

    private void failTimes(String username, int times) {
        for (int i = 0; i < times; i++) {
            catchFailure(username, "wrong-password");
        }
    }

    private Throwable catchFailure(String username, String password) {
        try {
            service.signIn(username, password);
        } catch (RuntimeException e) {
            return e;
        }
        throw new AssertionError("로그인이 실패해야 합니다: " + username);
    }

    private static UserCredentialRepository mockRepositoryWith(Map<String, UserCredential> store) {
        UserCredentialRepository repository = mock(UserCredentialRepository.class);
        when(repository.findByUsername(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(store.get(inv.getArgument(0))));
        return repository;
    }
}
