import Foundation
import WidgetKit

struct LevelEntry: TimelineEntry {
    let date: Date
    /// nil이면 로그인 전이거나 아직 Watch 앱이 데이터를 받지 않았다.
    let snapshot: CharacterSnapshot?

    /// 마지막 동기화가 오래돼 "업데이트 필요"를 보여야 하는지.
    var isStale: Bool {
        snapshot?.isStale(at: date) ?? false
    }
}

/// Watch 앱이 App Group에 캐시한 마지막 스냅샷을 보여 준다.
/// Watch 앱이 새 데이터를 받으면 `reloadAllTimelines()`로 바로 갱신하고,
/// 그렇지 않아도 30분마다 다시 읽어 오래된 스냅샷을 "업데이트 필요"로 바꾼다.
struct LevelProvider: TimelineProvider {
    static let refreshInterval: TimeInterval = 30 * 60

    var store = SnapshotStore()

    func entry(at date: Date) -> LevelEntry {
        LevelEntry(date: date, snapshot: store.load())
    }

    func timeline(at date: Date) -> Timeline<LevelEntry> {
        Timeline(entries: [entry(at: date)], policy: .after(Self.nextRefresh(after: date)))
    }

    static func nextRefresh(after date: Date) -> Date {
        date.addingTimeInterval(refreshInterval)
    }

    func placeholder(in context: Context) -> LevelEntry {
        LevelEntry(date: Date(), snapshot: .preview)
    }

    func getSnapshot(in context: Context, completion: @escaping (LevelEntry) -> Void) {
        let current = entry(at: Date())
        completion(context.isPreview && current.snapshot == nil ? placeholder(in: context) : current)
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<LevelEntry>) -> Void) {
        completion(timeline(at: Date()))
    }
}

extension CharacterSnapshot {
    /// 위젯 갤러리·placeholder용 예시 값.
    static var preview: CharacterSnapshot {
        CharacterSnapshot(
            level: 3,
            currentXp: 40,
            nextLevelXp: 100,
            quests: DailyQuestProgress(steps: 5_200, workoutMinutes: 20, sleepMinutes: 400),
            syncedAt: Date()
        )
    }
}
