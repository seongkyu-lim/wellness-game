import Foundation

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

    static func fraction(_ value: Int, of goal: Int) -> Double {
        guard goal > 0 else { return 0 }
        return min(max(Double(value) / Double(goal), 0), 1)
    }
}

/// Watch 앱이 마지막으로 받은 캐릭터 상태. 컴플리케이션(위젯)이 App Group으로 읽는다.
struct CharacterSnapshot: Codable, Equatable {
    let level: Int
    let currentXp: Int
    let nextLevelXp: Int
    let quests: DailyQuestProgress
    /// 서버에서 받아 온 시각.
    let syncedAt: Date

    var xpProgress: Double {
        LevelProgress.fraction(currentXp: currentXp, nextLevelXp: nextLevelXp)
    }

    var stage: GrowthStage {
        GrowthStage.stage(for: level)
    }
}

/// App Group UserDefaults에 마지막 스냅샷을 JSON으로 보관한다. Watch 앱이 쓰고 위젯이 읽는다.
struct SnapshotStore {
    static let appGroupIdentifier = "group.com.example.WellnessGame"
    static let key = "watch.characterSnapshot"

    let defaults: UserDefaults

    /// App Group 컨테이너를 쓸 수 없으면(엔타이틀먼트 누락 등) 앱 전용 UserDefaults로 대체한다.
    init(defaults: UserDefaults = UserDefaults(suiteName: SnapshotStore.appGroupIdentifier) ?? .standard) {
        self.defaults = defaults
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
