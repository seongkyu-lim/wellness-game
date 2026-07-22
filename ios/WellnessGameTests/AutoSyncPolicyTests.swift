import XCTest

final class AutoSyncPolicyTests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 1_800_000_000)

    func test_shouldSync_whenNeverSyncedBefore() {
        XCTAssertTrue(AutoSyncPolicy.shouldSync(lastSyncedAt: nil, now: now))
    }

    func test_shouldNotSync_withinMinimumInterval() {
        let justSynced = now.addingTimeInterval(-60)
        XCTAssertFalse(AutoSyncPolicy.shouldSync(lastSyncedAt: justSynced, now: now))
    }

    func test_shouldSync_afterMinimumIntervalElapsed() {
        let staleSync = now.addingTimeInterval(-301)
        XCTAssertTrue(AutoSyncPolicy.shouldSync(lastSyncedAt: staleSync, now: now))
    }

    func test_shouldNotSync_exactlyAtBoundary() {
        let boundary = now.addingTimeInterval(-AutoSyncPolicy.minimumInterval)
        XCTAssertFalse(AutoSyncPolicy.shouldSync(lastSyncedAt: boundary, now: now), "경계값에서는 아직 동기화하지 않아야 합니다")
    }

    func test_shouldSync_whenLastSyncIsInFuture_treatsAsStale() {
        // 기기 시간 변경 등으로 미래 시각이 저장돼도 동기화가 영구히 막히면 안 된다.
        let future = now.addingTimeInterval(3600)
        XCTAssertTrue(AutoSyncPolicy.shouldSync(lastSyncedAt: future, now: now))
    }
}
