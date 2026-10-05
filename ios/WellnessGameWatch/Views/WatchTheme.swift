import SwiftUI

/// iOS `Theme`의 팔레트(`ThemePalette`)를 Watch용으로 가볍게 옮긴 토큰.
/// watchOS는 항상 어두운 배경이므로 다크 값만 쓰고, 굵은 외곽선·오프셋 그림자는 작은 화면에서 생략한다.
enum WatchTheme {
    static let background = Color(hex: ThemePalette.background.dark)
    static let surface = Color(hex: ThemePalette.surface.dark)
    static let track = Color(hex: ThemePalette.track.dark)
    static let textPrimary = Color(hex: ThemePalette.textPrimary.dark)
    static let textSecondary = Color(hex: ThemePalette.textSecondary.dark)
    static let primary = Color(hex: ThemePalette.primary.dark)
    static let onPop = Color(hex: ThemePalette.onPop)

    static let xp = Color(hex: ThemePalette.xp)
    static let yellow = Color(hex: ThemePalette.yellow)
    static let mint = Color(hex: ThemePalette.mint)
    static let peach = Color(hex: ThemePalette.peach)
    static let lilac = Color(hex: ThemePalette.lilac)

    static let cardRadius: CGFloat = 14
}

extension Font {
    /// 제목·숫자용 굵은 둥근 글꼴 (iOS `Font.display` 대응).
    static func watchDisplay(_ style: Font.TextStyle) -> Font {
        .system(style, design: .rounded).weight(.black)
    }

    static func watchRounded(_ style: Font.TextStyle, weight: Font.Weight = .semibold) -> Font {
        .system(style, design: .rounded).weight(weight)
    }
}

/// 둥근 채색 진행 바. 트랙과 채움 색은 팔레트를 따른다.
struct WatchProgressBar: View {
    let progress: Double
    var tint: Color = WatchTheme.xp
    var height: CGFloat = 8

    var body: some View {
        GeometryReader { proxy in
            ZStack(alignment: .leading) {
                Capsule().fill(WatchTheme.track)
                Capsule()
                    .fill(tint)
                    .frame(width: proxy.size.width * min(max(progress, 0), 1))
            }
        }
        .frame(height: height)
        .accessibilityHidden(true)
    }
}
