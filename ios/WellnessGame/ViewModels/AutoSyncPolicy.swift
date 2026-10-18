import Foundation

/// 자동 동기화 실행 여부 판단 — 포그라운드 복귀가 잦아도 서버를 과도하게 호출하지 않도록 최소 간격을 둔다.
enum AutoSyncPolicy {
    static let minimumInterval: TimeInterval = 300

    static func shouldSync(lastSyncedAt: Date?, now: Date) -> Bool {
        guard let lastSyncedAt else {
            return true
        }
        // 기기 시간 변경 등으로 미래 시각이 저장된 경우 동기화가 영구히 막히지 않도록 stale로 취급한다.
        if lastSyncedAt > now {
            return true
        }
        return now.timeIntervalSince(lastSyncedAt) > minimumInterval
    }
}
