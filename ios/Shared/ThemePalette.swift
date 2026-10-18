import SwiftUI

/// 셀 애니메이션('애니') 스타일 팔레트의 hex 값. iOS `Theme`과 Watch·위젯이 함께 쓰는 단일 출처다.
/// UIKit에 의존하지 않도록 값만 둔다. 라이트/다크 전환은 각 플랫폼의 테마가 맡는다(Watch는 다크 값만 쓴다).
/// 웹(web/src/theme.css)과 같은 값을 유지해야 한다.
enum ThemePalette {
    /// 라이트/다크 모드에 따라 달라지는 색.
    struct Adaptive: Equatable {
        let light: UInt32
        let dark: UInt32
    }

    // MARK: - Surfaces

    static let background = Adaptive(light: 0xFFF7E8, dark: 0x14172B)
    static let surface = Adaptive(light: 0xFFFFFF, dark: 0x262B4D)
    static let ink = Adaptive(light: 0x1F2340, dark: 0x070914)
    static let popShadow = Adaptive(light: 0x1F2340, dark: 0x6C5CE7)
    static let track = Adaptive(light: 0xE6ECFA, dark: 0x3A4070)
    static let fieldBorder = Adaptive(light: 0x1F2340, dark: 0x8A90C8)

    // MARK: - Text

    static let textPrimary = Adaptive(light: 0x1F2340, dark: 0xF3F0FF)
    static let textSecondary = Adaptive(light: 0x4A5080, dark: 0xB4B9E0)
    static let onPop: UInt32 = 0x1F2340

    // MARK: - Brand · accents

    static let primary = Adaptive(light: 0x3F8F3A, dark: 0x8ED65A)
    static let xp: UInt32 = 0x9BE15D
    static let yellow: UInt32 = 0xFFE066
    static let pink: UInt32 = 0xFFB3CF
    static let mint: UInt32 = 0xCFF3B5
    static let peach: UInt32 = 0xFFD9C7
    static let lilac: UInt32 = 0xE3D9FF
    static let sparklePink: UInt32 = 0xFF9EC4

    static let dangerBackground = Adaptive(light: 0xFFD6D0, dark: 0x5A2230)
    static let dangerText = Adaptive(light: 0x8A1F12, dark: 0xFFD6D0)

    // MARK: - Hero scene

    static let sky = Adaptive(light: 0xBFE6FF, dark: 0x2B3470)
    static let hill = Adaptive(light: 0x9BDB6A, dark: 0x3E7A4A)
    static let hillDeep = Adaptive(light: 0x7CC44E, dark: 0x2F6239)

    // MARK: - Stat colors

    static let statStrength: UInt32 = 0xFF9A6C
    static let statVitality: UInt32 = 0x7DD484
    static let statDiscipline: UInt32 = 0x7FB8F7
    static let statRecovery: UInt32 = 0xB9A2F5
}

extension Color {
    /// 모드와 상관없이 같은 hex 색상.
    init(hex: UInt32) {
        self.init(
            red: Double((hex >> 16) & 0xFF) / 255,
            green: Double((hex >> 8) & 0xFF) / 255,
            blue: Double(hex & 0xFF) / 255
        )
    }
}
