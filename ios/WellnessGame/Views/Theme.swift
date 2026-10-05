import SwiftUI
import UIKit

/// 셀 애니메이션('애니') 스타일 디자인 토큰. 웹(web/src/theme.css)과 동일한 팔레트를 쓴다.
/// 라이트 = 크림 종이 + 하늘, 다크 = 밤하늘 + 네온 보라 그림자.
enum Theme {
    // MARK: - Surfaces

    /// 화면 배경 — 크림 종이 / 밤하늘 남색
    static let background = Color(light: 0xFFF7E8, dark: 0x14172B)
    /// 카드 표면
    static let surface = Color(light: 0xFFFFFF, dark: 0x262B4D)
    /// 굵은 외곽선(잉크)
    static let ink = Color(light: 0x1F2340, dark: 0x070914)
    /// 카드 뒤 하드 오프셋 그림자 — 다크에서는 네온 보라
    static let popShadow = Color(light: 0x1F2340, dark: 0x6C5CE7)
    /// 진행바 트랙
    static let track = Color(light: 0xE6ECFA, dark: 0x3A4070)
    /// 입력칸 테두리 — 다크 모드에서도 3:1 이상 보이게 한다
    static let fieldBorder = Color(light: 0x1F2340, dark: 0x8A90C8)

    // MARK: - Text

    static let textPrimary = Color(light: 0x1F2340, dark: 0xF3F0FF)
    static let textSecondary = Color(light: 0x4A5080, dark: 0xB4B9E0)
    /// 채색 면(칩·밴드·노란 버튼) 위의 글자색 — 라이트/다크 모두 잉크
    static let onPop = Color(hex: 0x1F2340)

    // MARK: - Brand · accents

    /// 브랜드 그린 — 섹션 마크, 'Game' 글자, 강조
    static let primary = Color(light: 0x3F8F3A, dark: 0x8ED65A)
    static let xp = Color(hex: 0x9BE15D)
    static let yellow = Color(hex: 0xFFE066)
    static let pink = Color(hex: 0xFFB3CF)
    static let mint = Color(hex: 0xCFF3B5)
    static let peach = Color(hex: 0xFFD9C7)
    static let lilac = Color(hex: 0xE3D9FF)
    /// 반짝이 장식 분홍
    static let sparklePink = Color(hex: 0xFF9EC4)

    static let dangerBackground = Color(light: 0xFFD6D0, dark: 0x5A2230)
    static let dangerText = Color(light: 0x8A1F12, dark: 0xFFD6D0)

    // MARK: - Hero scene

    static let sky = Color(light: 0xBFE6FF, dark: 0x2B3470)
    static let hill = Color(light: 0x9BDB6A, dark: 0x3E7A4A)
    static let hillDeep = Color(light: 0x7CC44E, dark: 0x2F6239)

    // MARK: - Stat colors (채색 밴드 — 위 글자/아이콘은 onPop)

    static let statStrength = Color(hex: 0xFF9A6C)
    static let statVitality = Color(hex: 0x7DD484)
    static let statDiscipline = Color(hex: 0x7FB8F7)
    static let statRecovery = Color(hex: 0xB9A2F5)

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

    /// 모드와 상관없이 같은 hex 색상.
    init(hex: UInt32) {
        self.init(
            red: Double((hex >> 16) & 0xFF) / 255,
            green: Double((hex >> 8) & 0xFF) / 255,
            blue: Double(hex & 0xFF) / 255
        )
    }
}
