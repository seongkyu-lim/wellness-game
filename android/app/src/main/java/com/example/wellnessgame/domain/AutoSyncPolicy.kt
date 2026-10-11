package com.example.wellnessgame.domain

import java.time.Duration
import java.time.Instant

/** 포그라운드 복귀가 잦아도 서버를 과도하게 호출하지 않도록 최소 간격을 둔다(iOS `AutoSyncPolicy`). */
object AutoSyncPolicy {
    val MINIMUM_INTERVAL: Duration = Duration.ofMinutes(5)

    fun shouldSync(lastSyncedAt: Instant?, now: Instant): Boolean {
        if (lastSyncedAt == null) return true
        // 기기 시간 변경 등으로 미래 시각이 저장돼도 동기화가 영구히 막히지 않게 한다.
        if (lastSyncedAt.isAfter(now)) return true
        return Duration.between(lastSyncedAt, now) > MINIMUM_INTERVAL
    }
}
