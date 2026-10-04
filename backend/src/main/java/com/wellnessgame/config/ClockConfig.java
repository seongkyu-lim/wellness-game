package com.wellnessgame.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

/**
 * 서버 기준 현재 시각과 시간대. 동기화 날짜 창(오늘 ±1일, 활동 시각 범위)은 이 Clock 의 zone 으로 계산한다.
 * 테스트에서는 가변 Clock 으로 대체한다.
 */
@Configuration
public class ClockConfig {
    @Bean
    public Clock clock(@Value("${app.timezone:Asia/Seoul}") String timezone) {
        return Clock.system(ZoneId.of(timezone));
    }
}
