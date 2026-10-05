package com.wellnessgame.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 아이디별 연속 로그인 실패 횟수와 잠금 상태(#55). 존재하지 않는 아이디도 똑같이 세어 잠금 여부로 계정 존재가 드러나지 않게 한다.
 *
 * <p>단일 인스턴스 인메모리 저장이라 재시작하면 초기화되고 여러 인스턴스 사이에 공유되지 않는다.
 * 마지막 실패 후 잠금 시간이 지나면 실패 횟수도 잊으며, 그런 항목은 주기적으로 지워 메모리에 쌓이지 않게 한다.
 */
class LoginAttemptGuard {
    /** 유효한 아이디는 32자 이하다. 그보다 긴 입력으로 메모리를 키우지 못하게 키 길이를 자른다. */
    static final int MAX_KEY_LENGTH = 64;
    private static final long PURGE_INTERVAL_MILLIS = 60_000;
    private static final Logger log = LoggerFactory.getLogger(LoginAttemptGuard.class);

    private final LoginLockoutProperties properties;
    private final Clock clock;
    private final Map<String, Attempts> attempts = new ConcurrentHashMap<>();
    private final AtomicLong nextPurgeAtMillis;

    LoginAttemptGuard(LoginLockoutProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
        this.nextPurgeAtMillis = new AtomicLong(clock.millis() + PURGE_INTERVAL_MILLIS);
    }

    boolean isLocked(String username) {
        if (!properties.enabled()) {
            return false;
        }
        long now = clock.millis();
        purgeExpiredIfDue(now);
        Attempts current = attempts.get(key(username));
        return current != null && current.lockedUntilMillis > now;
    }

    void recordFailure(String username) {
        if (!properties.enabled()) {
            return;
        }
        long now = clock.millis();
        long lockoutMillis = properties.lockout().toMillis();
        Attempts updated = attempts.compute(key(username), (k, existing) -> {
            int failures = existing == null || existing.isExpired(now, lockoutMillis) ? 1 : existing.failures + 1;
            if (failures >= properties.maxFailedLogins()) {
                // 잠금이 풀리면 다시 maxFailedLogins 회의 기회를 준다.
                return new Attempts(0, now, now + lockoutMillis);
            }
            return new Attempts(failures, now, existing == null ? 0 : existing.lockedUntilMillis);
        });
        if (updated.failures == 0) { // 방금 잠긴 경우에만 0 이다.
            log.warn("로그인 잠금: 연속 {}회 실패, {}분 동안 잠금", properties.maxFailedLogins(), properties.lockoutMinutes());
        }
    }

    void recordSuccess(String username) {
        attempts.remove(key(username));
    }

    int trackedKeys() {
        return attempts.size();
    }

    private void purgeExpiredIfDue(long now) {
        long due = nextPurgeAtMillis.get();
        if (now < due || !nextPurgeAtMillis.compareAndSet(due, now + PURGE_INTERVAL_MILLIS)) {
            return;
        }
        long lockoutMillis = properties.lockout().toMillis();
        attempts.values().removeIf(entry -> entry.isExpired(now, lockoutMillis));
    }

    /** 대소문자만 다른 아이디(DB 콜레이션상 같은 계정일 수 있음)를 같은 키로 센다. */
    private static String key(String username) {
        String normalized = username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
        return normalized.length() > MAX_KEY_LENGTH ? normalized.substring(0, MAX_KEY_LENGTH) : normalized;
    }

    private record Attempts(int failures, long lastFailureMillis, long lockedUntilMillis) {
        /** 잠금이 끝났고 마지막 실패로부터 잠금 시간만큼 지났으면 더 기억할 필요가 없다. */
        boolean isExpired(long now, long lockoutMillis) {
            return lockedUntilMillis <= now && now >= lastFailureMillis + lockoutMillis;
        }
    }
}
