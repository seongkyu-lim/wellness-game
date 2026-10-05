import SwiftUI
import WidgetKit

@main
struct WellnessGameWatchWidgets: WidgetBundle {
    var body: some Widget {
        LevelComplication()
    }
}

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

    private let xpColor = Color(hex: ThemePalette.xp)
    private let signInText = Text("iPhone에서 로그인하세요")
    private let staleText = Text("업데이트 필요")

    var body: some View {
        content
            // 잠금·항상 켜짐 화면에서는 레벨·XP를 가린다.
            .privacySensitive()
    }

    @ViewBuilder
    private var content: some View {
        switch family {
        case .accessoryCircular:
            circular
        case .accessoryRectangular:
            rectangular
        default:
            inline
        }
    }

    private func percentText(_ snapshot: CharacterSnapshot) -> String {
        snapshot.xpProgress.formatted(.percent.precision(.fractionLength(0)))
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
            .opacity(entry.isStale ? 0.5 : 1)
            .accessibilityLabel(Text("Lv.\(snapshot.level)"))
            .accessibilityValue(entry.isStale ? staleText : Text(percentText(snapshot)))
        } else {
            Image(systemName: "leaf.fill")
                .font(.title3)
                .accessibilityLabel(signInText)
        }
    }

    @ViewBuilder
    private var rectangular: some View {
        VStack(alignment: .leading, spacing: 2) {
            if let snapshot = entry.snapshot {
                Text("Lv.\(snapshot.level) · \(snapshot.stage.displayName)")
                    .font(.system(.headline, design: .rounded).weight(.black))
                    .widgetAccentable()
                Gauge(value: snapshot.xpProgress) {
                    EmptyView()
                }
                .gaugeStyle(.accessoryLinearCapacity)
                .tint(xpColor)
                Group {
                    if entry.isStale {
                        staleText
                    } else {
                        Text("\(snapshot.currentXp) / \(snapshot.nextLevelXp) XP")
                    }
                }
                .font(.system(.caption2, design: .rounded))
                .foregroundStyle(.secondary)
            } else {
                Text(verbatim: "Wellness Game")
                    .font(.system(.headline, design: .rounded).weight(.black))
                    .widgetAccentable()
                signInText
                    .font(.system(.caption2, design: .rounded))
                    .foregroundStyle(.secondary)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    @ViewBuilder
    private var inline: some View {
        if let snapshot = entry.snapshot {
            if entry.isStale {
                Text("Lv.\(snapshot.level) · 업데이트 필요")
            } else {
                Text("Lv.\(snapshot.level) · XP \(percentText(snapshot))")
            }
        } else {
            signInText
        }
    }
}
