package com.wellnessgame.ratelimit;

import com.wellnessgame.support.MutableClock;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class FixedWindowRateLimiterTest {
    private static final Instant NOW = Instant.parse("2026-10-04T00:00:00Z");

    private final MutableClock clock = new MutableClock(NOW);
    private final FixedWindowRateLimiter limiter = new FixedWindowRateLimiter(clock, Duration.ofMinutes(1));

    @Test
    void allowsUpToLimitThenRejectsWithRetryAfter() {
        for (int i = 0; i < 3; i++) {
            assertThat(limiter.tryAcquire("k", 3).allowed()).isTrue();
        }
        clock.setInstant(NOW.plusSeconds(20));

        FixedWindowRateLimiter.Decision denied = limiter.tryAcquire("k", 3);

        assertThat(denied.allowed()).isFalse();
        assertThat(denied.retryAfterSeconds()).isEqualTo(40);
    }

    @Test
    void retryAfterRoundsUpToAtLeastOneSecond() {
        limiter.tryAcquire("k", 1);
        clock.setInstant(NOW.plusMillis(59_500));

        assertThat(limiter.tryAcquire("k", 1).retryAfterSeconds()).isEqualTo(1);
    }

    @Test
    void allowsAgainAfterWindowElapses() {
        limiter.tryAcquire("k", 1);
        assertThat(limiter.tryAcquire("k", 1).allowed()).isFalse();

        clock.setInstant(NOW.plus(Duration.ofMinutes(1)));

        assertThat(limiter.tryAcquire("k", 1).allowed()).isTrue();
        assertThat(limiter.tryAcquire("k", 1).allowed()).isFalse();
    }

    @Test
    void keysAreIndependent() {
        limiter.tryAcquire("user-a", 1);

        assertThat(limiter.tryAcquire("user-a", 1).allowed()).isFalse();
        assertThat(limiter.tryAcquire("user-b", 1).allowed()).isTrue();
    }

    @Test
    void nonPositiveLimitDisablesLimiting() {
        for (int i = 0; i < 100; i++) {
            assertThat(limiter.tryAcquire("k", 0).allowed()).isTrue();
            assertThat(limiter.tryAcquire("k", -1).allowed()).isTrue();
        }
        assertThat(limiter.trackedKeys()).isZero();
    }

    @Test
    void purgesExpiredKeys() {
        for (int i = 0; i < 100; i++) {
            limiter.tryAcquire("ip-" + i, 5);
        }
        assertThat(limiter.trackedKeys()).isEqualTo(100);

        clock.setInstant(NOW.plus(Duration.ofMinutes(1)));
        limiter.tryAcquire("fresh", 5);

        assertThat(limiter.trackedKeys()).isEqualTo(1);
    }

    @Test
    void purgeKeepsActiveWindows() {
        limiter.tryAcquire("old", 5);
        clock.setInstant(NOW.plusSeconds(30));
        limiter.tryAcquire("recent", 5);
        clock.setInstant(NOW.plusSeconds(60));

        limiter.tryAcquire("recent", 5);

        assertThat(limiter.trackedKeys()).isEqualTo(1);
    }

    @Test
    void clientIpIgnoresForwardedHeaderUnlessTrusted() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.1");
        request.addHeader("X-Forwarded-For", "1.2.3.4, 203.0.113.7");

        assertThat(ClientIpResolver.resolve(request, false)).isEqualTo("10.0.0.1");
        assertThat(ClientIpResolver.resolve(request, true)).isEqualTo("203.0.113.7");
    }

    @Test
    void clientIpFallsBackToRemoteAddrWhenTrustedHeaderMissingOrBlank() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.1");
        assertThat(ClientIpResolver.resolve(request, true)).isEqualTo("10.0.0.1");

        request.addHeader("X-Forwarded-For", " , ");
        assertThat(ClientIpResolver.resolve(request, true)).isEqualTo("10.0.0.1");
    }
}
