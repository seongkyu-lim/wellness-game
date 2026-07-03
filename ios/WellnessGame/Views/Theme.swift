import SwiftUI
import UIKit

/// 라이트 그린 웰니스 디자인 시스템의 컬러 · 수치 토큰.
enum Theme {
    // MARK: - Core palette

    /// 화면 배경 — 연한 연두빛 크림
    static let background = Color(light: 0xF3F8EC, dark: 0x141812)
    /// 카드 표면
    static let surface = Color(light: 0xFFFFFF, dark: 0x1C221A)
    /// 옅은 연두 틴트 — 칩, 아이콘 배경, 프로그레스 트랙
    static let surfaceTint = Color(light: 0xE7F3DA, dark: 0x27311F)

    /// 주요 텍스트 — 딥 포레스트
    static let textPrimary = Color(light: 0x24382A, dark: 0xE9F1E4)
    /// 보조 텍스트
    static let textSecondary = Color(light: 0x6D806E, dark: 0x9CAB9B)

    /// 브랜드 그린 — 주요 버튼, 강조
    static let primary = Color(light: 0x4C8C46, dark: 0x6FBE62)
    /// 라임 액센트 — XP, 성장 표현
    static let lime = Color(light: 0x7CC142, dark: 0x93D65A)
    /// 레벨업 · 하이라이트
    static let amber = Color(light: 0xE89B2D, dark: 0xF0B35B)

    // MARK: - Stat colors

    static let statStrength = Color(light: 0xE07A4F, dark: 0xE8936F)
    static let statVitality = Color(light: 0x55A85E, dark: 0x74C57D)
    static let statDiscipline = Color(light: 0x4E86C6, dark: 0x74A6D9)
    static let statRecovery = Color(light: 0x8A70CE, dark: 0xA692DC)

    // MARK: - Gradients

    /// 캐릭터 히어로 카드 배경 — 연둣빛 그라데이션
    static let heroGradient = LinearGradient(
        colors: [Color(light: 0xDFF2C6, dark: 0x2A3820), Color(light: 0xC2E69A, dark: 0x35482A)],
        startPoint: .topLeading,
        endPoint: .bottomTrailing
    )
    /// XP 링 그라데이션
    static let xpGradient = AngularGradient(
        colors: [Color(light: 0x9BD65C, dark: 0xA8E06C), Color(light: 0x5CA843, dark: 0x74C25A)],
        center: .center,
        startAngle: .degrees(-90),
        endAngle: .degrees(270)
    )

    // MARK: - Metrics

    static let cardRadius: CGFloat = 20
    static let chipRadius: CGFloat = 12
    static let sectionSpacing: CGFloat = 16
}

extension Color {
    /// 라이트/다크 모드에 따라 다른 hex 값을 쓰는 색상.
    init(light: UInt32, dark: UInt32) {
        self.init(uiColor: UIColor { traits in
            traits.userInterfaceStyle == .dark ? UIColor(hex: dark) : UIColor(hex: light)
        })
    }
}

private extension UIColor {
    convenience init(hex: UInt32) {
        self.init(
            red: CGFloat((hex >> 16) & 0xFF) / 255,
            green: CGFloat((hex >> 8) & 0xFF) / 255,
            blue: CGFloat(hex & 0xFF) / 255,
            alpha: 1
        )
    }
}
