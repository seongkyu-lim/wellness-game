import SwiftUI

// MARK: - Card

extension View {
    /// 흰 표면 + 라운드 + 은은한 그림자의 기본 섹션 카드.
    func wellnessCard(padding: CGFloat = 18) -> some View {
        self
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(padding)
            .background(Theme.surface, in: RoundedRectangle(cornerRadius: Theme.cardRadius, style: .continuous))
            .shadow(color: .black.opacity(0.05), radius: 10, y: 4)
    }
}

// MARK: - Section header

struct SectionHeader: View {
    let title: String
    var icon: String?

    var body: some View {
        HStack(spacing: 6) {
            if let icon {
                Image(systemName: icon)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(Theme.primary)
            }
            Text(title)
                .font(.headline)
                .foregroundStyle(Theme.textPrimary)
        }
    }
}

// MARK: - XP ring

struct XPRingView: View {
    let level: Int
    let progress: Double
    var size: CGFloat = 108
    var lineWidth: CGFloat = 11

    var body: some View {
        ZStack {
            Circle()
                .stroke(Theme.surfaceTint, lineWidth: lineWidth)
            Circle()
                .trim(from: 0, to: max(0.015, min(progress, 1)))
                .stroke(Theme.xpGradient, style: StrokeStyle(lineWidth: lineWidth, lineCap: .round))
                .rotationEffect(.degrees(-90))
                .animation(.easeOut(duration: 0.8), value: progress)
            VStack(spacing: 0) {
                Text("Lv.")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(Theme.textSecondary)
                Text("\(level)")
                    .font(.system(size: 34, weight: .bold, design: .rounded))
                    .foregroundStyle(Theme.textPrimary)
                    .contentTransition(.numericText())
            }
        }
        .frame(width: size, height: size)
    }
}

// MARK: - Stat tile

struct StatTile: View {
    let title: String
    let subtitle: String
    let value: Int
    let icon: String
    let color: Color

    var body: some View {
        VStack(spacing: 6) {
            Image(systemName: icon)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(color)
                .frame(width: 32, height: 32)
                .background(color.opacity(0.14), in: Circle())
            Text(value.formatted())
                .font(.system(.headline, design: .rounded).weight(.bold))
                .foregroundStyle(Theme.textPrimary)
                .contentTransition(.numericText())
            VStack(spacing: 1) {
                Text(title)
                    .font(.caption2.weight(.semibold))
                    .foregroundStyle(Theme.textSecondary)
                Text(subtitle)
                    .font(.caption2)
                    .foregroundStyle(Theme.textSecondary.opacity(0.8))
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 12)
        .background(Theme.surfaceTint.opacity(0.55), in: RoundedRectangle(cornerRadius: Theme.chipRadius + 2, style: .continuous))
    }
}

// MARK: - Metric card (걸음 · 수면)

struct MetricCard: View {
    let title: String
    let value: String
    let icon: String
    let color: Color

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Image(systemName: icon)
                .font(.headline)
                .foregroundStyle(color)
                .frame(width: 38, height: 38)
                .background(color.opacity(0.14), in: RoundedRectangle(cornerRadius: Theme.chipRadius, style: .continuous))
            Text(value)
                .font(.system(.title3, design: .rounded).weight(.bold))
                .foregroundStyle(Theme.textPrimary)
                .lineLimit(1)
                .minimumScaleFactor(0.7)
            Text(title)
                .font(.caption)
                .foregroundStyle(Theme.textSecondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .wellnessCard(padding: 16)
    }
}

// MARK: - Pill badge

struct PillBadge: View {
    let text: String
    var color: Color = Theme.lime

    var body: some View {
        Text(text)
            .font(.caption.weight(.bold))
            .foregroundStyle(color)
            .padding(.horizontal, 10)
            .padding(.vertical, 5)
            .background(color.opacity(0.15), in: Capsule())
    }
}

// MARK: - Buttons

struct PrimaryActionButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.headline)
            .foregroundStyle(.white)
            .frame(maxWidth: .infinity)
            .padding(.vertical, 15)
            .background(Theme.primary, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
            .opacity(configuration.isPressed ? 0.8 : 1)
            .scaleEffect(configuration.isPressed ? 0.98 : 1)
            .animation(.easeOut(duration: 0.12), value: configuration.isPressed)
    }
}

struct SecondaryActionButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.subheadline.weight(.semibold))
            .foregroundStyle(Theme.primary)
            .frame(maxWidth: .infinity)
            .padding(.vertical, 13)
            .background(Theme.surfaceTint, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
            .opacity(configuration.isPressed ? 0.7 : 1)
            .animation(.easeOut(duration: 0.12), value: configuration.isPressed)
    }
}

// MARK: - Workout icon

extension WorkoutType {
    var iconName: String {
        switch self {
        case .swimming: "figure.pool.swim"
        case .running: "figure.run"
        case .walking: "figure.walk"
        case .cycling: "figure.outdoor.cycle"
        case .strengthTraining: "dumbbell.fill"
        case .other: "figure.mixed.cardio"
        }
    }

    var displayName: String {
        switch self {
        case .swimming: "수영"
        case .running: "달리기"
        case .walking: "걷기"
        case .cycling: "자전거"
        case .strengthTraining: "근력 운동"
        case .other: "운동"
        }
    }
}
