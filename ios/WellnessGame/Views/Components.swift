import SwiftUI

// MARK: - Cel card

extension View {
    /// 셀 애니메이션풍 카드 — 카드 면 + 3pt 잉크 외곽선 + 블러 없는 오프셋 그림자.
    func celCard(
        padding: CGFloat = 16,
        radius: CGFloat = Theme.cardRadius,
        fill: Color = Theme.surface,
        shadow: CGFloat = 4
    ) -> some View {
        self
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(padding)
            .celOutline(radius: radius, fill: fill, shadow: shadow)
    }

    /// 크기를 바꾸지 않고 면 · 외곽선 · 오프셋 그림자만 입힌다. shadow가 0이면 그림자를 생략한다.
    func celOutline(
        radius: CGFloat,
        fill: Color = Theme.surface,
        lineWidth: CGFloat = Theme.line,
        shadow: CGFloat = 0
    ) -> some View {
        let shape = RoundedRectangle(cornerRadius: radius, style: .continuous)
        return self
            .background(shape.fill(fill))
            .overlay(shape.strokeBorder(Theme.ink, lineWidth: lineWidth))
            .background {
                if shadow > 0 {
                    shape.fill(Theme.popShadow).offset(x: shadow, y: shadow)
                }
            }
    }

    /// 가로 기울임(CSS skewX). 뷰 중심을 기준으로 기울인다.
    func skewedX(degrees: Double) -> some View {
        modifier(SkewX(degrees: degrees))
    }
}

private struct SkewX: GeometryEffect {
    var degrees: Double

    func effectValue(size: CGSize) -> ProjectionTransform {
        let shear = CGFloat(tan(degrees * .pi / 180))
        return ProjectionTransform(CGAffineTransform(a: 1, b: 0, c: shear, d: 1, tx: -shear * size.height / 2, ty: 0))
    }
}

// MARK: - Section header

/// 기울어진 초록 막대 마크 + 굵은 제목.
struct SectionHeader: View {
    let title: LocalizedStringKey

    var body: some View {
        HStack(spacing: 10) {
            RoundedRectangle(cornerRadius: 3, style: .continuous)
                .fill(Theme.primary)
                .overlay(RoundedRectangle(cornerRadius: 3, style: .continuous).strokeBorder(Theme.ink, lineWidth: 2.5))
                .frame(width: 10, height: 22)
                .skewedX(degrees: -12)
                .accessibilityHidden(true)
            Text(title)
                .font(.display(.title3))
                .foregroundStyle(Theme.textPrimary)
        }
        .accessibilityAddTraits(.isHeader)
    }
}

// MARK: - EXP bar

/// 사선 줄무늬가 흐르는 EXP 게이지. 동작 줄이기가 켜져 있으면 줄무늬를 멈춘다.
struct XPBarView: View {
    let progress: Double
    var height: CGFloat = 24

    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    /// 줄무늬 한 주기의 가로 폭 (45° 7pt 줄무늬 ≈ 14·√2).
    private static let stripePeriod: CGFloat = 20
    /// 진행률 0이어도 채움이 살짝 보이도록 하는 최소 폭.
    static let minimumFillWidth: CGFloat = 10

    /// 트랙 폭 기준 채움 폭 — 진행률을 0...1로 자르고 최소 폭을 보장한다.
    static func fillWidth(progress: Double, totalWidth: CGFloat) -> CGFloat {
        let clamped = CGFloat(min(max(progress, 0), 1))
        return min(max(totalWidth * clamped, minimumFillWidth), totalWidth)
    }

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: 14, style: .continuous)
        GeometryReader { proxy in
            let width = Self.fillWidth(progress: progress, totalWidth: proxy.size.width)
            ZStack(alignment: .leading) {
                Theme.track
                fill
                    .frame(width: width)
                    .overlay(alignment: .trailing) {
                        Rectangle().fill(Theme.ink).frame(width: Theme.line)
                    }
                    .clipped()
                    .animation(reduceMotion ? nil : .easeOut(duration: 0.8), value: width)
            }
        }
        .frame(height: height)
        .clipShape(shape)
        .overlay(shape.strokeBorder(Theme.ink, lineWidth: Theme.line))
    }

    @ViewBuilder
    private var fill: some View {
        let stripes = Stripes(period: Self.stripePeriod)
            .fill(Color.white.opacity(0.45))
            .padding(.leading, -Self.stripePeriod)
        ZStack(alignment: .leading) {
            Theme.xp
            if reduceMotion {
                stripes
            } else {
                stripes.keyframeAnimator(initialValue: CGFloat(0), repeating: true) { content, offset in
                    content.offset(x: offset)
                } keyframes: { _ in
                    LinearKeyframe(Self.stripePeriod, duration: 0.86)
                }
            }
        }
    }
}

/// 45° 사선 줄무늬. 주기의 절반은 채우고 절반은 비운다.
private struct Stripes: Shape {
    let period: CGFloat

    func path(in rect: CGRect) -> Path {
        var path = Path()
        let band = period / 2
        var x = rect.minX - rect.height
        while x < rect.maxX {
            path.move(to: CGPoint(x: x, y: rect.maxY))
            path.addLine(to: CGPoint(x: x + rect.height, y: rect.minY))
            path.addLine(to: CGPoint(x: x + rect.height + band, y: rect.minY))
            path.addLine(to: CGPoint(x: x + band, y: rect.maxY))
            path.closeSubpath()
            x += period
        }
        return path
    }
}

// MARK: - Stat tile

/// 채색 밴드 + 아이콘 + 큰 숫자 + "STR 근력".
struct StatTile: View {
    /// STR·VIT 같은 약어. 번역하지 않는다.
    let title: String
    let subtitle: LocalizedStringKey
    let value: Int
    let icon: String
    let color: Color

    private var name: Text {
        Text(verbatim: "\(title) ") + Text(subtitle)
    }

    var body: some View {
        VStack(spacing: 0) {
            Image(systemName: icon)
                .font(.system(size: 18, weight: .heavy))
                .foregroundStyle(Theme.onPop)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 7)
                .background(color)
                .overlay(alignment: .bottom) {
                    Rectangle().fill(Theme.ink).frame(height: Theme.line)
                }
            Text(value.formatted())
                .font(.display(.title))
                .foregroundStyle(Theme.textPrimary)
                .lineLimit(1)
                .minimumScaleFactor(0.6)
                .contentTransition(.numericText())
                .padding(.top, 8)
                .padding(.horizontal, 4)
            name
                .font(.rounded(.caption))
                .foregroundStyle(Theme.textPrimary)
                .lineLimit(1)
                .minimumScaleFactor(0.7)
                .padding(.top, 2)
                .padding(.bottom, 8)
                .padding(.horizontal, 2)
        }
        .frame(maxWidth: .infinity)
        .background(Theme.surface)
        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).strokeBorder(Theme.ink, lineWidth: Theme.line))
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(name)
        .accessibilityValue(value.formatted())
    }
}

// MARK: - Quest card (걸음 · 수면 · 운동 · 획득 내역)

/// 틴트 아이콘 상자 + 제목/부제 + 오른쪽 칩.
struct QuestCard<Trailing: View>: View {
    let icon: String
    let tint: Color
    let title: String
    var subtitle: String?
    @ViewBuilder var trailing: Trailing

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: icon)
                .font(.system(size: 20, weight: .heavy))
                .foregroundStyle(Theme.onPop)
                .frame(width: 46, height: 46)
                .celOutline(radius: Theme.chipRadius, fill: tint)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.rounded(.body))
                    .foregroundStyle(Theme.textPrimary)
                if let subtitle {
                    Text(subtitle)
                        .font(.rounded(.footnote, weight: .medium))
                        .foregroundStyle(Theme.textSecondary)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            trailing
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
        .celOutline(radius: 18)
        .accessibilityElement(children: .combine)
    }
}

extension QuestCard where Trailing == EmptyView {
    init(icon: String, tint: Color, title: String, subtitle: String? = nil) {
        self.init(icon: icon, tint: tint, title: title, subtitle: subtitle) { EmptyView() }
    }
}

// MARK: - Pill badge (chip)

/// 잉크 테두리 칩. 채색 면 위 글자는 라이트/다크 모두 잉크(onPop).
struct PillBadge: View {
    let text: String
    var fill: Color = Theme.yellow
    var foreground: Color = Theme.onPop

    var body: some View {
        Text(text)
            .font(.rounded(.subheadline))
            .foregroundStyle(foreground)
            .lineLimit(1)
            .padding(.horizontal, 10)
            .padding(.vertical, 3)
            .background(Capsule().fill(fill))
            .overlay(Capsule().strokeBorder(Theme.ink, lineWidth: 2.5))
    }
}

extension PillBadge {
    /// 카드색 칩 — 일반 텍스트 색을 쓴다.
    static func muted(_ text: String) -> PillBadge {
        PillBadge(text: text, fill: Theme.surface, foreground: Theme.textPrimary)
    }
}

// MARK: - Sparkle

/// 네 갈래 반짝이 별 (24×24 좌표계, 웹 Icons.tsx의 SPARKLE_PATH와 동일).
struct SparkleShape: Shape {
    func path(in rect: CGRect) -> Path {
        let sx = rect.width / 24, sy = rect.height / 24
        let points: [(CGFloat, CGFloat)] = [
            (12, 0), (14.5, 9.5), (24, 12), (14.5, 14.5), (12, 24), (9.5, 14.5), (0, 12), (9.5, 9.5),
        ]
        var path = Path()
        for (index, point) in points.enumerated() {
            let p = CGPoint(x: rect.minX + point.0 * sx, y: rect.minY + point.1 * sy)
            index == 0 ? path.move(to: p) : path.addLine(to: p)
        }
        path.closeSubpath()
        return path
    }
}

/// 반짝이 장식 — 동작 줄이기가 켜져 있으면 깜빡이지 않는다.
struct SparkleView: View {
    let size: CGFloat
    let color: Color
    var period: Double = 0.9

    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        let star = SparkleShape()
            .fill(color)
            .overlay(SparkleShape().stroke(Theme.onPop, style: StrokeStyle(lineWidth: 1.8 * size / 24, lineJoin: .round)))
            .frame(width: size, height: size)
        Group {
            if reduceMotion {
                star
            } else {
                star.phaseAnimator([false, true]) { content, bright in
                    content
                        .scaleEffect(bright ? 1.1 : 0.6)
                        .rotationEffect(.degrees(bright ? 25 : 0))
                        .opacity(bright ? 1 : 0.5)
                } animation: { _ in
                    .easeInOut(duration: period)
                }
            }
        }
        .accessibilityHidden(true)
    }
}

// MARK: - Bobbing

extension View {
    /// 둥실둥실 위아래로 흔들리는 움직임. 동작 줄이기가 켜져 있으면 멈춘다.
    func bobbing() -> some View {
        modifier(Bobbing())
    }
}

private struct Bobbing: ViewModifier {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    func body(content: Content) -> some View {
        if reduceMotion {
            content
        } else {
            content.phaseAnimator([false, true]) { view, up in
                view
                    .rotationEffect(.degrees(up ? 1.5 : -1.5), anchor: UnitPoint(x: 0.5, y: 0.9))
                    .offset(y: up ? -8 : 0)
            } animation: { _ in
                .easeInOut(duration: 1.3)
            }
        }
    }
}

// MARK: - Buttons

/// 셀 스타일 버튼 — 잉크 테두리 + 아래로 깔린 하드 그림자. 누르면 그림자 쪽으로 눌린다.
struct CelButtonStyle: ButtonStyle {
    var fill: Color = Theme.yellow
    var foreground: Color = Theme.onPop
    var height: CGFloat = 54
    var radius: CGFloat = 16
    var shadow = CGSize(width: 0, height: 5)
    var font: Font = .rounded(.headline, weight: .bold)
    var expands = true

    func makeBody(configuration: Configuration) -> some View {
        let pressed = configuration.isPressed
        let shape = RoundedRectangle(cornerRadius: radius, style: .continuous)
        let travel = CGSize(width: shadow.width * 0.75, height: shadow.height * 0.75)
        configuration.label
            .font(font)
            .foregroundStyle(foreground)
            .padding(.horizontal, 16)
            .frame(maxWidth: expands ? .infinity : nil, minHeight: height)
            .background(shape.fill(fill))
            .overlay(shape.strokeBorder(Theme.ink, lineWidth: Theme.line))
            .offset(pressed ? travel : .zero)
            .background(shape.fill(Theme.popShadow).offset(shadow))
            .contentShape(shape)
            .animation(.easeOut(duration: 0.08), value: pressed)
    }
}

extension ButtonStyle where Self == CelButtonStyle {
    /// 노란 주요 버튼
    static var celPrimary: CelButtonStyle { CelButtonStyle() }
    /// 카드색 보조 버튼
    static var celSecondary: CelButtonStyle { CelButtonStyle(fill: Theme.surface, foreground: Theme.textPrimary) }
}

// MARK: - Display logic

extension CharacterState {
    /// 현재 레벨에서의 XP 진행률 (0...1). nextLevelXp가 0 이하이면 0.
    var xpProgress: Double {
        guard nextLevelXp > 0 else { return 0 }
        return min(max(Double(currentXp) / Double(nextLevelXp), 0), 1)
    }
}

extension DailyHealthSnapshot {
    /// 수면 시간 표시 문자열. 수면 기록이나 분 데이터가 없으면 "기록 없음".
    var sleepDurationText: String {
        sleepDurationText(in: .main)
    }

    /// 지정한 번들(언어)로 만든 수면 시간 문자열. 테스트에서 언어별 결과를 검증할 때 쓴다.
    func sleepDurationText(in bundle: Bundle) -> String {
        guard let minutes = sleep?.sleepMinutes else {
            return String(localized: "기록 없음", bundle: bundle)
        }
        let hours = minutes / 60
        let remainder = minutes % 60
        return String(localized: "\(hours)시간 \(remainder)분", bundle: bundle)
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
        displayName(in: .main)
    }

    /// 지정한 번들(언어)로 만든 표시 이름.
    func displayName(in bundle: Bundle) -> String {
        switch self {
        case .swimming: String(localized: "수영", bundle: bundle)
        case .running: String(localized: "달리기", bundle: bundle)
        case .walking: String(localized: "걷기", bundle: bundle)
        case .cycling: String(localized: "자전거", bundle: bundle)
        case .strengthTraining: String(localized: "근력 운동", bundle: bundle)
        case .other: String(localized: "운동", bundle: bundle)
        }
    }
}
