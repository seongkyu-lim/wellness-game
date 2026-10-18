package com.wellnessgame.support;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * 테스트별로 현재 시각을 바꿀 수 있는 Clock (UTC).
 */
public class MutableClock extends Clock {
    private volatile Instant instant;

    public MutableClock(Instant instant) {
        this.instant = instant;
    }

    public void setInstant(Instant instant) {
        this.instant = instant;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return Clock.fixed(instant, zone);
    }

    @Override
    public Instant instant() {
        return instant;
    }
}
