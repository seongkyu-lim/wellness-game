package com.wellnessgame.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 주기 작업(리프레시 토큰 정리 등)을 켠다. */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
