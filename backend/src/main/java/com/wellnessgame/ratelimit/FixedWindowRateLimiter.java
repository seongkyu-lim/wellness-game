package com.wellnessgame.ratelimit;

import com.wellnessgame.common.BoundedExpiringStore;

import java.time.Clock;
import java.time.Duration;

/**
 * 키별 고정 윈도우 카운터. 윈도우가 시작된 뒤 {@code window} 동안 {@code limit} 회까지 허용한다.
 *
 * <p>단일 인스턴스 인메모리 구현이다. 여러 인스턴스가 한도를 공유하려면 Redis 등 공용 저장소가 필요하다.
 * 고정 윈도우라서 경계 직전·직후에 몰리면 짧은 순간 최대 2배까지 통과할 수 있다(단순함과 메모리를 택한 절충).
 *
 * <p>만료된 키는 {@code window} 마다 정리하고, 키 수가 {@code maxKeys} 에 닿으면 가장 오래된 윈도우부터 내보낸다
 * ({@link BoundedExpiringStore}).
 */
public class FixedWindowRateLimiter {
    private final Clock clock;
    private final long windowMillis;
    private final BoundedExpiringStore<Bucket> buckets;

    public FixedWindowRateLimiter(Clock clock, Duration window, int maxKeys) {
        if (window == null || window.toMillis() < 1) {
            throw new IllegalArgumentException("rate limit window must be positive");
        }
        this.clock = clock;
        this.windowMillis = window.toMillis();
        this.buckets = new BoundedExpiringStore<>(clock, windowMillis, maxKeys, Bucket::windowEndMillis);
    }

    /**
     * 요청 한 건을 기록하고 허용 여부를 돌려준다. {@code limit <= 0} 이면 항상 허용(비활성화)한다.
     */
    public Decision tryAcquire(String key, int limit) {
        if (limit <= 0) {
            return Decision.ALLOWED;
        }
        long now = clock.millis();
        Bucket bucket = buckets.compute(key, (k, existing) -> {
            if (existing == null || now >= existing.windowEndMillis()) {
                return new Bucket(now + windowMillis, 1);
            }
            return new Bucket(existing.windowEndMillis(), existing.count() + 1);
        });
        if (bucket.count() <= limit) {
            return Decision.ALLOWED;
        }
        long remainingMillis = bucket.windowEndMillis() - now;
        // 올림해서 최소 1초. Retry-After 가 0 이면 클라이언트가 즉시 재시도한다.
        long retryAfterSeconds = Math.max(1, (remainingMillis + 999) / 1000);
        return new Decision(false, retryAfterSeconds);
    }

    /** 현재 추적 중인 키 수(테스트·모니터링용). */
    int trackedKeys() {
        return buckets.size();
    }

    private record Bucket(long windowEndMillis, int count) {
    }

    public record Decision(boolean allowed, long retryAfterSeconds) {
        static final Decision ALLOWED = new Decision(true, 0);
    }
}
