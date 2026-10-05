package com.wellnessgame.auth;

import com.wellnessgame.common.BoundedExpiringStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.Locale;

/**
 * 로그인 실패 횟수와 잠금(#55). 두 단계로 센다.
 * <ul>
 *   <li>(아이디, 클라이언트 IP): {@code maxFailedLogins} 회. 공격자가 자기 IP 에서 실패해도 피해자의 다른 IP 는 잠기지 않는다.</li>
 *   <li>아이디 전체: {@code maxFailedLoginsPerUsername} 회. 여러 IP 로 나눠 시도하는 분산 공격을 막는다.</li>
 * </ul>
 *
 * <p>비밀번호 비교 전에 {@link #tryReserve} 로 시도를 먼저 센다. 계산이 키별로 원자적이라 동시 요청이 몰려도 한도보다 많은
 * 비교가 일어나지 않는다. 성공하면 {@link #recordSuccess} 가 (아이디, IP) 기록을 지우고 아이디 전체 기록에서는 이번 예약
 * 한 건만 되돌린다(공격자의 실패 누적을 정상 로그인이 지워 주지 않게). 마지막 실패 후 잠금 시간이 지나면 기록을 잊는다.
 *
 * <p>존재하지 않는 아이디도 똑같이 세어 잠금 반응으로 계정 존재가 드러나지 않게 한다. 단일 인스턴스 인메모리 저장이다.
 */
class LoginAttemptGuard {
    /** 유효한 아이디는 32자 이하다. 그보다 긴 입력으로 메모리를 키우지 못하게 키 길이를 자른다. */
    static final int MAX_USERNAME_KEY_LENGTH = 64;
    private static final long PURGE_INTERVAL_MILLIS = 60_000;
    private static final Logger log = LoggerFactory.getLogger(LoginAttemptGuard.class);

    private final LoginLockoutProperties properties;
    private final Clock clock;
    private final long lockoutMillis;
    private final BoundedExpiringStore<Attempts> perClient;
    private final BoundedExpiringStore<Attempts> perUsername;

    LoginAttemptGuard(LoginLockoutProperties properties, Clock clock, int maxKeys) {
        this.properties = properties;
        this.clock = clock;
        this.lockoutMillis = properties.lockout().toMillis();
        this.perClient = new BoundedExpiringStore<>(clock, PURGE_INTERVAL_MILLIS, maxKeys, Attempts::expiresAtMillis);
        this.perUsername = new BoundedExpiringStore<>(clock, PURGE_INTERVAL_MILLIS, maxKeys, Attempts::expiresAtMillis);
    }

    /**
     * 로그인 시도 한 건을 실패로 미리 센다. 이미 잠겨 있으면 세지 않고 false.
     * 한도에 닿는 시도 자체는 허용되고(성공하면 풀림), 그다음 시도부터 거부된다.
     */
    boolean tryReserve(String username, String clientIp) {
        String user = usernameKey(username);
        String client = clientKey(user, clientIp);
        if (!reserve(perClient, client, properties.maxFailedLogins())) {
            return false;
        }
        if (!reserve(perUsername, user, properties.maxFailedLoginsPerUsername())) {
            release(perClient, client); // 아이디 잠금으로 거부된 시도는 비밀번호 추측이 아니므로 되돌린다.
            return false;
        }
        return true;
    }

    void recordSuccess(String username, String clientIp) {
        String user = usernameKey(username);
        perClient.remove(clientKey(user, clientIp));
        release(perUsername, user);
    }

    int trackedKeys() {
        return perClient.size() + perUsername.size();
    }

    private boolean reserve(BoundedExpiringStore<Attempts> store, String key, int max) {
        if (max <= 0) {
            return true;
        }
        long now = clock.millis();
        boolean[] reserved = {false};
        Attempts updated = store.compute(key, (k, existing) -> {
            if (existing == null || now >= existing.expiresAtMillis()) {
                reserved[0] = true;
                return new Attempts(1, now + lockoutMillis);
            }
            if (existing.failures() >= max) {
                return existing; // 잠김: 세지도, 잠금을 늘리지도 않는다.
            }
            reserved[0] = true;
            return new Attempts(existing.failures() + 1, now + lockoutMillis);
        });
        if (reserved[0] && updated.failures() == max) {
            log.warn("로그인 잠금: {} 기준 실패 {}회, {}분 동안 잠금",
                    store == perUsername ? "아이디 전체" : "아이디·IP", max, properties.lockoutMinutes());
        }
        return reserved[0];
    }

    /** 예약 한 건을 되돌린다. 0 이 되면 기록을 지운다. */
    private static void release(BoundedExpiringStore<Attempts> store, String key) {
        store.compute(key, (k, existing) -> existing == null || existing.failures() <= 1
                ? null
                : new Attempts(existing.failures() - 1, existing.expiresAtMillis()));
    }

    /** 대소문자만 다른 아이디(DB 콜레이션상 같은 계정일 수 있음)를 같은 키로 센다. */
    private static String usernameKey(String username) {
        String normalized = username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
        return normalized.length() > MAX_USERNAME_KEY_LENGTH
                ? normalized.substring(0, MAX_USERNAME_KEY_LENGTH)
                : normalized;
    }

    private static String clientKey(String userKey, String clientIp) {
        return userKey + '|' + (clientIp == null ? "" : clientIp);
    }

    /**
     * @param failures        마지막 실패 후 잠금 시간 안에 쌓인 실패(예약) 수. 한도 이상이면 잠김.
     * @param expiresAtMillis 마지막 실패 + 잠금 시간. 이 시각이 지나면 기록과 잠금이 함께 사라진다.
     */
    private record Attempts(int failures, long expiresAtMillis) {
    }
}
