import Foundation

/// 레벨 안에서의 XP 진행률 계산. iOS 캐릭터 카드, Watch 화면, 컴플리케이션이 같은 규칙을 쓴다.
enum LevelProgress {
    /// 현재 레벨에서의 XP 진행률 (0...1). nextLevelXp가 0 이하이면 0.
    static func fraction(currentXp: Int, nextLevelXp: Int) -> Double {
        guard nextLevelXp > 0 else { return 0 }
        return min(max(Double(currentXp) / Double(nextLevelXp), 0), 1)
    }
}
