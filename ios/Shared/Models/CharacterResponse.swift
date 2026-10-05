import Foundation

// 서버 DTO(com.wellnessgame.api)와 필드명이 1:1로 일치해야 한다. iOS 앱과 Watch 앱이 함께 쓴다.

struct HealthActivitySyncResponse: Decodable {
    let userId: String
    let date: String
    let gainedXp: Int
    let levelUp: Bool
    let character: CharacterState
    let activityResults: [ActivityXpResult]
}

struct CharacterState: Decodable, Equatable {
    let level: Int
    let currentXp: Int
    let totalXp: Int
    let nextLevelXp: Int
    let stats: CharacterStats
}

struct CharacterStats: Decodable, Equatable {
    let str: Int
    let vit: Int
    let intStat: Int
    let discipline: Int
    let recovery: Int
}

struct ActivityXpResult: Identifiable, Decodable {
    var id: String {
        "\(type)-\(source)-\(message)"
    }

    let type: String
    let source: String
    let gainedXp: Int
    let message: String
    let duplicate: Bool
}

extension CharacterState {
    /// 현재 레벨에서의 XP 진행률 (0...1). nextLevelXp가 0 이하이면 0.
    var xpProgress: Double {
        LevelProgress.fraction(currentXp: currentXp, nextLevelXp: nextLevelXp)
    }
}
