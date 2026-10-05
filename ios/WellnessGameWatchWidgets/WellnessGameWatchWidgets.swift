import SwiftUI
import WidgetKit

@main
struct WellnessGameWatchWidgets: WidgetBundle {
    var body: some Widget {
        LevelComplication()
    }
}

// MARK: - Timeline

struct LevelEntry: TimelineEntry {
    let date: Date
    /// nil이면 로그인 전이거나 아직 Watch 앱이 데이터를 받지 않았다.
    let snapshot: CharacterSnapshot?
}

/// Watch 앱이 App Group에 캐시한 마지막 스냅샷을 보여 준다.
/// 데이터는 Watch 앱이 새로 받을 때 `reloadAllTimelines()`로 갱신하므로 스스로 다시 읽지 않는다.
struct LevelProvider: TimelineProvider {
    private let store = SnapshotStore()

    func placeholder(in context: Context) -> LevelEntry {
        LevelEntry(date: Date(), snapshot: .preview)
    }

    func getSnapshot(in context: Context, completion: @escaping (LevelEntry) -> Void) {
        completion(LevelEntry(date: Date(), snapshot: context.isPreview ? store.load() ?? .preview : store.load()))
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<LevelEntry>) -> Void) {
        completion(Timeline(entries: [LevelEntry(date: Date(), snapshot: store.load())], policy: .never))
    }
}

extension CharacterSnapshot {
    /// 위젯 갤러리·placeholder용 예시 값.
    static let preview = CharacterSnapshot(
        level: 3,
        currentXp: 40,
        nextLevelXp: 100,
        quests: DailyQuestProgress(steps: 5_200, workoutMinutes: 20, sleepMinutes: 400),
        syncedAt: Date()
    )
}

// MARK: - Widget

struct LevelComplication: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "LevelComplication", provider: LevelProvider()) { entry in
            LevelComplicationView(entry: entry)
                .containerBackground(.fill.tertiary, for: .widget)
        }
        .configurationDisplayName("레벨과 XP")
        .description("새싹이의 레벨과 XP 진행률을 보여 줘요.")
        .supportedFamilies([.accessoryCircular, .accessoryRectangular, .accessoryInline])
    }
}

struct LevelComplicationView: View {
    @Environment(\.widgetFamily) private var family
    let entry: LevelEntry

    private var xpColor: Color { Color(hex: ThemePalette.xp) }

    var body: some View {
        switch family {
        case .accessoryCircular:
            circular
        case .accessoryRectangular:
            rectangular
        default:
            inline
        }
    }

    @ViewBuilder
    private var circular: some View {
        if let snapshot = entry.snapshot {
            Gauge(value: snapshot.xpProgress) {
                Text(verbatim: "Lv")
            } currentValueLabel: {
                Text(verbatim: "\(snapshot.level)")
                    .font(.system(.title3, design: .rounded).weight(.black))
            }
            .gaugeStyle(.accessoryCircular)
            .tint(xpColor)
            .accessibilityLabel(Text("Lv.\(snapshot.level)"))
            .accessibilityValue(Text(snapshot.xpProgress.formatted(.percent.precision(.fractionLength(0)))))
        } else {
            Image(systemName: "leaf.fill")
                .font(.title3)
                .accessibilityLabel(Text("iPhone에서 로그인하세요"))
        }
    }

    @ViewBuilder
    private var rectangular: some View {
        if let snapshot = entry.snapshot {
            VStack(alignment: .leading, spacing: 2) {
                Text("Lv.\(snapshot.level) · \(snapshot.stage.displayName)")
                    .font(.system(.headline, design: .rounded).weight(.black))
                    .widgetAccentable()
                Gauge(value: snapshot.xpProgress) {
                    EmptyView()
                }
                .gaugeStyle(.accessoryLinearCapacity)
                .tint(xpColor)
                Text("\(snapshot.currentXp) / \(snapshot.nextLevelXp) XP")
                    .font(.system(.caption2, design: .rounded))
                    .foregroundStyle(.secondary)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        } else {
            VStack(alignment: .leading, spacing: 2) {
                Text(verbatim: "Wellness Game")
                    .font(.system(.headline, design: .rounded).weight(.black))
                    .widgetAccentable()
                Text("iPhone에서 로그인하세요")
                    .font(.system(.caption2, design: .rounded))
                    .foregroundStyle(.secondary)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    @ViewBuilder
    private var inline: some View {
        if let snapshot = entry.snapshot {
            Text("Lv.\(snapshot.level) · XP \(snapshot.xpProgress.formatted(.percent.precision(.fractionLength(0))))")
        } else {
            Text("iPhone에서 로그인하세요")
        }
    }
}
