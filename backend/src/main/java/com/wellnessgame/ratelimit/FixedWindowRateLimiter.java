package com.wellnessgame.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 키별 고정 윈도우 카운터. 윈도우가 시작된 뒤 {@code window} 동안 {@code limit} 회까지 허용한다.
 *
 * <p>단일 인스턴스 인메모리 구현이다. 여러 인스턴스가 한도를 공유하려면 Redis 등 공용 저장소가 필요하다.
 * 고정 윈도우라서 경계 직전·직후에 몰리면 짧은 순간 최대 2배까지 통과할 수 있다(단순함과 메모리를 택한 절충).
 *
 * <p>만료된 키는 {@code window} 마다 한 번씩 요청 경로에서 정리해 키가 계속 쌓이지 않게 한다.
 */
public class FixedWindowRateLimiter {
    private final Clock clock;
    private final Duration window;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final AtomicLong nextPurgeAtMillis;

    public FixedWindowRateLimiter(Clock clock, Duration window) {
        if (window == null || window.toMillis() < 1) {
            throw new IllegalArgumentException("rate limit window must be positive");
        }
        this.clock = clock;
        this.window = window;
        this.nextPurgeAtMillis = new AtomicLong(clock.millis() + window.toMillis());
    }

    /**
     * 요청 한 건을 기록하고 허용 여부를 돌려준다. {@code limit <= 0} 이면 항상 허용(비활성화)한다.
     */
    public Decision tryAcquire(String key, int limit) {
        if (limit <= 0) {
            return Decision.ALLOWED;
        }
        long now = clock.millis();
        purgeExpiredIfDue(now);
        long windowMillis = window.toMillis();
        Bucket bucket = buckets.compute(key, (k, existing) -> {
            if (existing == null || now >= existing.windowStartMillis + windowMillis) {
                return new Bucket(now, 1);
            }
            return new Bucket(existing.windowStartMillis, existing.count + 1);
        });
        if (bucket.count <= limit) {
            return Decision.ALLOWED;
        }
        long remainingMillis = bucket.windowStartMillis + windowMillis - now;
        // 올림해서 최소 1초. Retry-After 가 0 이면 클라이언트가 즉시 재시도한다.
        long retryAfterSeconds = Math.max(1, (remainingMillis + 999) / 1000);
        return new Decision(false, retryAfterSeconds);
    }

    /** 현재 추적 중인 키 수(테스트·모니터링용). */
    int trackedKeys() {
        return buckets.size();
    }

    private void purgeExpiredIfDue(long now) {
        long due = nextPurgeAtMillis.get();
        if (now < due || !nextPurgeAtMillis.compareAndSet(due, now + window.toMillis())) {
            return;
        }
        long windowMillis = window.toMillis();
        buckets.values().removeIf(bucket -> now >= bucket.windowStartMillis + windowMillis);
    }

    private record Bucket(long windowStartMillis, int count) {
    }

    public record Decision(boolean allowed, long retryAfterSeconds) {
        static final Decision ALLOWED = new Decision(true, 0);
    }
}
