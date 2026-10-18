import Foundation

/// 진행률 계산. XP 바(iOS 캐릭터 카드, Watch, 컴플리케이션)와 오늘의 퀘스트가 같은 규칙을 쓴다.
enum LevelProgress {
    /// `value / total`을 0...1로 자른 값. total이 0 이하이면 0.
    static func fraction(_ value: Int, of total: Int) -> Double {
        guard total > 0 else { return 0 }
        return min(max(Double(value) / Double(total), 0), 1)
    }
}
