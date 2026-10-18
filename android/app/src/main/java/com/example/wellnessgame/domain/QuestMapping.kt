package com.example.wellnessgame.domain

import com.example.wellnessgame.data.health.DailyHealthSnapshot
import com.example.wellnessgame.data.network.ActivityEntry
import com.example.wellnessgame.data.network.HealthActivityPayload

/**
 * 서버에 저장된 하루치 기록 → 퀘스트 현재값(iOS `DailyQuestProgress(activities:)`).
 * 걸음·수면은 날짜별 1건이 원칙이라 가장 큰 값을, 운동은 여러 번일 수 있어 합계를 쓴다.
 */
fun DailyQuestProgress.Companion.fromActivities(entries: List<ActivityEntry>): DailyQuestProgress {
    fun values(type: String, value: (ActivityEntry) -> Int?) = entries.filter { it.type == type }.mapNotNull(value)
    return DailyQuestProgress(
        steps = values(HealthActivityPayload.TYPE_STEPS) { it.steps }.maxOrNull() ?: 0,
        workoutMinutes = values(HealthActivityPayload.TYPE_WORKOUT) { it.durationMinutes }.sum(),
        sleepMinutes = values(HealthActivityPayload.TYPE_SLEEP) { it.sleepMinutes }.maxOrNull() ?: 0,
    )
}

/** 기기에서 읽은 오늘 데이터 → 퀘스트 현재값(서버 기록을 받지 못했을 때). */
fun DailyQuestProgress.Companion.fromSnapshot(snapshot: DailyHealthSnapshot) = DailyQuestProgress(
    steps = snapshot.steps,
    workoutMinutes = snapshot.workoutMinutes,
    sleepMinutes = snapshot.sleepMinutes,
)
