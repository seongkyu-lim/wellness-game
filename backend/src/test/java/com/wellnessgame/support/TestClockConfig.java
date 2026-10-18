package com.wellnessgame.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Instant;

@TestConfiguration
public class TestClockConfig {
    public static final Instant DEFAULT_NOW = Instant.parse("2026-06-17T12:00:00Z");

    @Bean
    @Primary
    public MutableClock testClock() {
        return new MutableClock(DEFAULT_NOW);
    }
}
