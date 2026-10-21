package com.wellnessgame.common;

import com.wellnessgame.support.MutableClock;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class BoundedExpiringStoreTest {
    private static final Instant NOW = Instant.parse("2026-10-04T00:00:00Z");

    private final MutableClock clock = new MutableClock(NOW);

    /** 값 = 만료 시각(epoch millis). */
    private BoundedExpiringStore<Long> store(long purgeMillis, int maxKeys) {
        return new BoundedExpiringStore<>(clock, purgeMillis, maxKeys, Long::longValue);
    }

    @Test
    void rejectsNonPositiveConfiguration() {
        assertThatIllegalArgumentException().isThrownBy(() -> store(0, 10));
        assertThatIllegalArgumentException().isThrownBy(() -> store(1000, 0));
    }

    @Test
    void computeGetAndRemoveWork() {
        BoundedExpiringStore<Long> s = store(1_000, 10);

        s.compute("a", (k, v) -> NOW.toEpochMilli() + 5_000);
        assertThat(s.get("a")).isEqualTo(NOW.toEpochMilli() + 5_000);
        assertThat(s.get("missing")).isNull();
        s.remove("a");
        assertThat(s.size()).isZero();
    }

    @Test
    void purgesExpiredEntriesOnlyWhenIntervalIsDue() {
        BoundedExpiringStore<Long> s = store(1_000, 10);
        s.compute("old", (k, v) -> NOW.toEpochMilli() + 100);

        // 정리 시각(1000ms) 전에는 만료돼도 남아 있다
        clock.setInstant(NOW.plusMillis(999));
        s.get("x");
        assertThat(s.size()).isEqualTo(1);

        // 정확히 정리 시각이 되면 지운다(만료 경계: now >= 만료)
        clock.setInstant(NOW.plusMillis(1_000));
        s.get("x");
        assertThat(s.size()).isZero();
    }

    @Test
    void keepsEntriesThatHaveNotExpiredAtPurgeTime() {
        BoundedExpiringStore<Long> s = store(1_000, 10);
        s.compute("alive", (k, v) -> NOW.toEpochMilli() + 5_000);
        s.compute("dead", (k, v) -> NOW.toEpochMilli() + 500);

        clock.setInstant(NOW.plusMillis(1_000));
        s.get("x");

        assertThat(s.get("alive")).isNotNull();
        assertThat(s.get("dead")).isNull();
    }

    @Test
    void evictsEarliestExpiringEntryWhenFullInsteadOfRejecting() {
        BoundedExpiringStore<Long> s = store(60_000, 3);
        long base = NOW.toEpochMilli();
        s.compute("a", (k, v) -> base + 3_000);
        s.compute("b", (k, v) -> base + 1_000); // 가장 먼저 만료
        s.compute("c", (k, v) -> base + 2_000);

        s.compute("d", (k, v) -> base + 4_000);

        assertThat(s.size()).isEqualTo(3);
        assertThat(s.get("b")).isNull();
        assertThat(s.get("d")).isNotNull();
    }

    @Test
    void existingKeyUpdateDoesNotEvictWhenFull() {
        BoundedExpiringStore<Long> s = store(60_000, 2);
        long base = NOW.toEpochMilli();
        s.compute("a", (k, v) -> base + 1_000);
        s.compute("b", (k, v) -> base + 2_000);

        s.compute("a", (k, v) -> v + 10);

        assertThat(s.size()).isEqualTo(2);
        assertThat(s.get("a")).isEqualTo(base + 1_010);
        assertThat(s.get("b")).isNotNull();
    }

    @Test
    void fullStorePurgesExpiredEntriesBeforeEvictingLiveOnes() {
        BoundedExpiringStore<Long> s = store(60_000, 2);
        long base = NOW.toEpochMilli();
        s.compute("expired", (k, v) -> base + 10);
        s.compute("live", (k, v) -> base + 50_000);
        clock.setInstant(NOW.plusMillis(100)); // 정리 주기 전이지만 expired 는 이미 만료

        s.compute("new", (k, v) -> base + 90_000);

        assertThat(s.get("live")).isNotNull(); // 만료 항목이 대신 지워져 살아 있는 항목은 보존
        assertThat(s.get("new")).isNotNull();
        assertThat(s.get("expired")).isNull();
    }

    @Test
    void evictsOnePercentAtOnceForLargeStores() {
        BoundedExpiringStore<Long> s = store(600_000, 200);
        long base = NOW.toEpochMilli();
        for (int i = 0; i < 200; i++) {
            long expires = base + 10_000 + i;
            s.compute("k" + i, (k, v) -> expires);
        }

        s.compute("extra", (k, v) -> base + 99_999);

        // overflow=1, maxKeys/100=2 이므로 가장 오래된 2개가 나가고 새 키가 들어온다
        assertThat(s.size()).isEqualTo(199);
        assertThat(s.get("k0")).isNull();
        assertThat(s.get("k1")).isNull();
        assertThat(s.get("k2")).isNotNull();
    }
}
