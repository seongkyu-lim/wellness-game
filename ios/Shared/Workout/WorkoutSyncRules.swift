import Foundation

/// 운동 기록을 서버 검증 규칙(SPEC.md §5)에 맞춰 보내기 위한 계산.
/// - 분 값: 서버는 `분 ≤ 구간(분, 내림) + 1`을 허용하므로 반올림한 활동 시간(일시정지 제외)은 항상 통과한다.
/// - 칼로리: 서버는 `칼로리 × 60 ≤ 20 × 구간(초)`를 넘으면 해당 운동을 건너뛴다. 넘는 몫은 잘라서 보낸다.
enum WorkoutSyncRules {
    /// 서버 `HealthActivitySyncValidator.MAX_CALORIES_PER_MINUTE`.
    static let maxCaloriesPerMinute: Double = 20
    /// 서버 `MINUTE_ROUNDING_TOLERANCE`.
    static let minuteRoundingTolerance = 1
    /// 시각은 초 단위 ISO-8601로 보내므로 시작·종료가 각각 잘리면 구간이 최대 1초 줄어든다.
    private static let encodingSlackSeconds: Double = 1

    /// 서버에 보내는 운동 분 값. 활동 시간(일시정지 제외)을 반올림하고 최소 1분으로 둔다.
    static func durationMinutes(activeDuration: TimeInterval) -> Int {
        max(1, Int((max(0, activeDuration) / 60).rounded()))
    }

    /// 서버가 받아들이는 분 값의 상한: 구간(분, 내림) + 1.
    static func maxAcceptedMinutes(start: Date, end: Date) -> Int {
        Int(max(0, end.timeIntervalSince(start)) / 60) + minuteRoundingTolerance
    }

    /// 구간 길이에서 허용되는 최대 칼로리. 시각 인코딩 오차만큼 구간을 짧게 잡아 보수적으로 계산한다.
    static func calorieCap(start: Date, end: Date) -> Double {
        let seconds = max(0, end.timeIntervalSince(start).rounded(.down) - encodingSlackSeconds)
        return maxCaloriesPerMinute * seconds / 60
    }

    /// 칼로리를 서버 상한 안으로 자른다(0.1kcal 단위 내림). 값이 없으면 nil 그대로.
    static func clampedCalories(_ calories: Double?, start: Date, end: Date) -> Double? {
        guard let calories else { return nil }
        let clamped = min(max(0, calories), calorieCap(start: start, end: end))
        return (clamped * 10).rounded(.down) / 10
    }
}
