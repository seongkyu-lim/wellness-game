package com.wellnessgame.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 만료·폐기된 리프레시 토큰 행을 하루 한 번 지운다(auth.refresh.cleanup-cron, 기본 매일 04:00, app.timezone 기준). */
@Component
public class RefreshTokenCleanupJob {
    private static final Logger log = LoggerFactory.getLogger(RefreshTokenCleanupJob.class);

    private final RefreshTokenService refreshTokenService;

    public RefreshTokenCleanupJob(RefreshTokenService refreshTokenService) {
        this.refreshTokenService = refreshTokenService;
    }

    @Scheduled(cron = "${auth.refresh.cleanup-cron:0 0 4 * * *}", zone = "${app.timezone:Asia/Seoul}")
    public void purge() {
        int deleted = refreshTokenService.purgeExpiredAndRevoked();
        log.info("리프레시 토큰 정리: {}건 삭제", deleted);
    }
}
