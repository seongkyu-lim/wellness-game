import Foundation

// 조회 API 응답 DTO. 서버(com.wellnessgame.api)·웹(web/src/api/types.ts)과 필드명이 1:1로 일치해야 한다.

/// `GET /api/characters/me` 응답.
struct CharacterSummaryResponse: Decodable, Equatable {
    let userId: String
    let character: CharacterState
}

/// `GET /api/health-activities?date=yyyy-MM-dd` 응답. iPhone이 동기화해 둔 하루치 활동 기록.
struct DailyActivitiesResponse: Decodable, Equatable {
    let userId: String
    let date: String
    let activities: [ActivityEntry]
}

/// 하루치 활동 기록 한 건. 화면에 필요한 필드만 디코딩한다.
struct ActivityEntry: Decodable, Equatable {
    /// STEPS · WORKOUT · SLEEP
    let type: String
    let durationMinutes: Int?
    let steps: Int?
    let sleepMinutes: Int?
    let gainedXp: Int
}

extension DailyQuestProgress {
    /// 하루치 활동 기록을 퀘스트 3종의 현재값으로 모은다.
    /// 걸음·수면은 날짜별 1건이 원칙이라 가장 큰 값을, 운동은 여러 번일 수 있어 합계를 쓴다.
    init(activities: [ActivityEntry]) {
        func values(_ type: String, _ value: (ActivityEntry) -> Int?) -> [Int] {
            activities.filter { $0.type == type }.compactMap(value)
        }
        self.init(
            steps: values("STEPS", \.steps).max() ?? 0,
            workoutMinutes: values("WORKOUT", \.durationMinutes).reduce(0, +),
            sleepMinutes: values("SLEEP", \.sleepMinutes).max() ?? 0
        )
    }
}
