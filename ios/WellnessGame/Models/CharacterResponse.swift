import Foundation

struct HealthActivitySyncResponse: Decodable {
    let userId: String
    let date: String
    let gainedXp: Int
    let levelUp: Bool
    let character: CharacterState
    let activityResults: [ActivityXpResult]
}

struct CharacterState: Decodable {
    let level: Int
    let currentXp: Int
    let totalXp: Int
    let nextLevelXp: Int
    let stats: CharacterStats
}

struct CharacterStats: Decodable {
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

