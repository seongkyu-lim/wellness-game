package com.wellnessgame.common;

import java.time.Clock;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;
import java.util.function.ToLongFunction;

/**
 * 만료 시각이 있는 값을 키별로 담는 인메모리 저장소. 레이트 리밋 카운터와 로그인 실패 기록이 함께 쓴다.
 *
 * <ul>
 *   <li>만료된 항목은 {@code purgeIntervalMillis} 마다 한 번 요청 경로에서 지운다.</li>
 *   <li>키 수가 {@code maxKeys} 에 닿은 상태에서 새 키가 들어오면 먼저 만료 항목을 지우고, 그래도 가득 차면
 *       만료가 가장 이른(가장 오래된) 항목을 내보낸다. 새 사용자를 거부하지 않기 위해 축출을 택했다.</li>
 * </ul>
 *
 * <p>상한 검사는 동시 요청 사이에서 근사적이다(잠깐 상한을 조금 넘을 수 있음). 단일 인스턴스 전용이다.
 */
public final class BoundedExpiringStore<V> {
    private final Clock clock;
    private final long purgeIntervalMillis;
    private final int maxKeys;
    private final ToLongFunction<V> expiresAtMillis;
    private final Map<String, V> entries = new ConcurrentHashMap<>();
    private final AtomicLong nextPurgeAtMillis;

    public BoundedExpiringStore(Clock clock, long purgeIntervalMillis, int maxKeys, ToLongFunction<V> expiresAtMillis) {
        if (purgeIntervalMillis < 1 || maxKeys < 1) {
            throw new IllegalArgumentException("purgeIntervalMillis and maxKeys must be positive");
        }
        this.clock = clock;
        this.purgeIntervalMillis = purgeIntervalMillis;
        this.maxKeys = maxKeys;
        this.expiresAtMillis = expiresAtMillis;
        this.nextPurgeAtMillis = new AtomicLong(clock.millis() + purgeIntervalMillis);
    }

    /** {@link Map#compute} 와 같다. 호출 전에 만료 정리와 상한 확보를 한다. */
    public V compute(String key, BiFunction<String, V, V> remapping) {
        long now = clock.millis();
        purgeExpiredIfDue(now);
        if (!entries.containsKey(key)) {
            ensureCapacity(now);
        }
        return entries.compute(key, remapping);
    }

    public V get(String key) {
        purgeExpiredIfDue(clock.millis());
        return entries.get(key);
    }

    public void remove(String key) {
        entries.remove(key);
    }

    public int size() {
        return entries.size();
    }

    private void purgeExpiredIfDue(long now) {
        long due = nextPurgeAtMillis.get();
        if (now >= due && nextPurgeAtMillis.compareAndSet(due, now + purgeIntervalMillis)) {
            purgeExpired(now);
        }
    }

    private void purgeExpired(long now) {
        entries.values().removeIf(value -> now >= expiresAtMillis.applyAsLong(value));
    }

    private void ensureCapacity(long now) {
        if (entries.size() < maxKeys) {
            return;
        }
        purgeExpired(now);
        int overflow = entries.size() - maxKeys + 1;
        if (overflow <= 0) {
            return;
        }
        // 가득 찬 상태가 이어질 때 요청마다 전체를 훑지 않도록 상한의 1% 를 한 번에 내보낸다.
        int evictCount = Math.max(overflow, maxKeys / 100);
        entries.entrySet().stream()
                .sorted(Comparator.comparingLong(entry -> expiresAtMillis.applyAsLong(entry.getValue())))
                .limit(evictCount)
                .toList()
                .forEach(oldest -> entries.remove(oldest.getKey(), oldest.getValue()));
    }
}
