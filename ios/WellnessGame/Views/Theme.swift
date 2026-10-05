import SwiftUI
import UIKit

/// 셀 애니메이션('애니') 스타일 디자인 토큰. hex 값은 Watch·위젯과 함께 쓰는 `ThemePalette`(Shared)에 있다.
/// 라이트 = 크림 종이 + 하늘, 다크 = 밤하늘 + 네온 보라 그림자.
enum Theme {
    // MARK: - Surfaces

    /// 화면 배경 — 크림 종이 / 밤하늘 남색
    static let background = Color(ThemePalette.background)
    /// 카드 표면
    static let surface = Color(ThemePalette.surface)
    /// 굵은 외곽선(잉크)
    static let ink = Color(ThemePalette.ink)
    /// 카드 뒤 하드 오프셋 그림자 — 다크에서는 네온 보라
    static let popShadow = Color(ThemePalette.popShadow)
    /// 진행바 트랙
    static let track = Color(ThemePalette.track)
    /// 입력칸 테두리 — 다크 모드에서도 3:1 이상 보이게 한다
    static let fieldBorder = Color(ThemePalette.fieldBorder)

    // MARK: - Text

    static let textPrimary = Color(ThemePalette.textPrimary)
    static let textSecondary = Color(ThemePalette.textSecondary)
    /// 채색 면(칩·밴드·노란 버튼) 위의 글자색 — 라이트/다크 모두 잉크
    static let onPop = Color(hex: ThemePalette.onPop)

    // MARK: - Brand · accents

    /// 브랜드 그린 — 섹션 마크, 'Game' 글자, 강조
    static let primary = Color(ThemePalette.primary)
    static let xp = Color(hex: ThemePalette.xp)
    static let yellow = Color(hex: ThemePalette.yellow)
    static let pink = Color(hex: ThemePalette.pink)
    static let mint = Color(hex: ThemePalette.mint)
    static let peach = Color(hex: ThemePalette.peach)
    static let lilac = Color(hex: ThemePalette.lilac)
    /// 반짝이 장식 분홍
    static let sparklePink = Color(hex: ThemePalette.sparklePink)

    static let dangerBackground = Color(ThemePalette.dangerBackground)
    static let dangerText = Color(ThemePalette.dangerText)

    // MARK: - Hero scene

    static let sky = Color(ThemePalette.sky)
    static let hill = Color(ThemePalette.hill)
    static let hillDeep = Color(ThemePalette.hillDeep)

    // MARK: - Stat colors (채색 밴드 — 위 글자/아이콘은 onPop)

    static let statStrength = Color(hex: ThemePalette.statStrength)
    static let statVitality = Color(hex: ThemePalette.statVitality)
    static let statDiscipline = Color(hex: ThemePalette.statDiscipline)
    static let statRecovery = Color(hex: ThemePalette.statRecovery)

    // MARK: - Metrics

    /// 외곽선 두께
    static let line: CGFloat = 3
    static let cardRadius: CGFloat = 22
    static let chipRadius: CGFloat = 14
    static let sectionSpacing: CGFloat = 18
}

// MARK: - Typography

extension Font {
    /// 제목·숫자용 굵은 둥근 글꼴 (웹의 Black Han Sans 대응). Dynamic Type을 따른다.
    static func display(_ style: Font.TextStyle) -> Font {
        .system(style, design: .rounded).weight(.black)
    }

    /// 본문용 둥근 글꼴 (웹의 Jua 대응).
    static func rounded(_ style: Font.TextStyle, weight: Font.Weight = .semibold) -> Font {
        .system(style, design: .rounded).weight(weight)
    }
}

extension Color {
    /// 라이트/다크 모드에 따라 다른 hex 값을 쓰는 색상.
    init(light: UInt32, dark: UInt32) {
        self.init(uiColor: UIColor { traits in
            UIColor(Color(hex: traits.userInterfaceStyle == .dark ? dark : light))
        })
    }

    /// 공용 팔레트의 라이트/다크 쌍.
    init(_ adaptive: ThemePalette.Adaptive) {
        self.init(light: adaptive.light, dark: adaptive.dark)
    }
}
