import Foundation
import os

/// 오늘의 퀘스트 3종(걸음·운동·수면)의 현재값과 목표값.
/// 목표값은 서버 `ActivityXpCalculator`의 STEPS_GOAL·WORKOUT_GOAL_MINUTES·SLEEP_GOAL_MINUTES와 같아야 한다.
struct DailyQuestProgress: Codable, Equatable {
    static let stepsGoal = 8_000
    static let workoutGoalMinutes = 30
    static let sleepGoalMinutes = 420

    var steps: Int
    var workoutMinutes: Int
    var sleepMinutes: Int

    init(steps: Int, workoutMinutes: Int, sleepMinutes: Int) {
        self.steps = steps
        self.workoutMinutes = workoutMinutes
        self.sleepMinutes = sleepMinutes
    }

    var stepsProgress: Double { LevelProgress.fraction(steps, of: Self.stepsGoal) }
    var workoutProgress: Double { LevelProgress.fraction(workoutMinutes, of: Self.workoutGoalMinutes) }
    var sleepProgress: Double { LevelProgress.fraction(sleepMinutes, of: Self.sleepGoalMinutes) }
}

/// Watch 앱이 마지막으로 받은 캐릭터 상태. 컴플리케이션(위젯)이 App Group으로 읽는다.
struct CharacterSnapshot: Codable, Equatable {
    /// 이보다 오래된 스냅샷은 "업데이트 필요"로 표시한다.
    static let staleInterval: TimeInterval = 2 * 60 * 60

    let level: Int
    let currentXp: Int
    let nextLevelXp: Int
    let quests: DailyQuestProgress
    /// 서버에서 받아 온 시각.
    let syncedAt: Date

    var xpProgress: Double {
        LevelProgress.fraction(currentXp, of: nextLevelXp)
    }

    var stage: GrowthStage {
        GrowthStage.stage(for: level)
    }

    func isStale(at now: Date) -> Bool {
        now.timeIntervalSince(syncedAt) > Self.staleInterval
    }
}

/// App Group UserDefaults에 마지막 스냅샷을 JSON으로 보관한다. Watch 앱이 쓰고 위젯이 읽는다.
struct SnapshotStore {
    static let appGroupIdentifier = "group.com.example.WellnessGame"
    static let key = "watch.characterSnapshot"

    private static let logger = Logger(subsystem: "com.example.WellnessGame", category: "SnapshotStore")

    let defaults: UserDefaults

    init(defaults: UserDefaults = SnapshotStore.appGroupDefaults()) {
        self.defaults = defaults
    }

    /// App Group 컨테이너. 쓸 수 없으면(엔타이틀먼트·그룹 등록 누락) Debug에서는 바로 알리고,
    /// Release에서는 로그를 남긴 뒤 앱 전용 UserDefaults로 대체한다(위젯은 데이터를 못 보게 된다).
    static func appGroupDefaults() -> UserDefaults {
        if let shared = UserDefaults(suiteName: appGroupIdentifier) {
            return shared
        }
        logger.error("App Group \(appGroupIdentifier, privacy: .public)을 열지 못해 standard UserDefaults로 대체합니다.")
        assertionFailure("App Group \(appGroupIdentifier) 엔타이틀먼트를 확인하세요.")
        return .standard
    }

    func load() -> CharacterSnapshot? {
        defaults.data(forKey: Self.key).flatMap { try? Self.decode($0) }
    }

    func save(_ snapshot: CharacterSnapshot) {
        guard let data = try? Self.encode(snapshot) else { return }
        defaults.set(data, forKey: Self.key)
    }

    func clear() {
        defaults.removeObject(forKey: Self.key)
    }

    static func encode(_ snapshot: CharacterSnapshot) throws -> Data {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        encoder.outputFormatting = .sortedKeys
        return try encoder.encode(snapshot)
    }

    static func decode(_ data: Data) throws -> CharacterSnapshot {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        return try decoder.decode(CharacterSnapshot.self, from: data)
    }
}
