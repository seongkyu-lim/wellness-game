import SwiftUI
import XCTest

final class ThemeColorTests: XCTestCase {
    private func resolvedRGB(_ color: Color, style: UIUserInterfaceStyle) -> (red: CGFloat, green: CGFloat, blue: CGFloat) {
        let resolved = UIColor(color).resolvedColor(with: UITraitCollection(userInterfaceStyle: style))
        var red: CGFloat = 0, green: CGFloat = 0, blue: CGFloat = 0, alpha: CGFloat = 0
        resolved.getRed(&red, green: &green, blue: &blue, alpha: &alpha)
        return (red, green, blue)
    }

    private func assertColor(
        _ color: Color,
        style: UIUserInterfaceStyle,
        hex: UInt32,
        file: StaticString = #filePath,
        line: UInt = #line
    ) {
        let rgb = resolvedRGB(color, style: style)
        let accuracy: CGFloat = 1.0 / 255.0
        XCTAssertEqual(rgb.red, CGFloat((hex >> 16) & 0xFF) / 255, accuracy: accuracy, "red", file: file, line: line)
        XCTAssertEqual(rgb.green, CGFloat((hex >> 8) & 0xFF) / 255, accuracy: accuracy, "green", file: file, line: line)
        XCTAssertEqual(rgb.blue, CGFloat(hex & 0xFF) / 255, accuracy: accuracy, "blue", file: file, line: line)
    }

    func test_adaptiveColor_resolvesLightHexInLightMode() {
        let color = Color(light: 0xF3F8EC, dark: 0x141812)
        assertColor(color, style: .light, hex: 0xF3F8EC)
    }

    func test_adaptiveColor_resolvesDarkHexInDarkMode() {
        let color = Color(light: 0xF3F8EC, dark: 0x141812)
        assertColor(color, style: .dark, hex: 0x141812)
    }

    func test_themeBackground_differsBetweenLightAndDarkMode() {
        let light = resolvedRGB(Theme.background, style: .light)
        let dark = resolvedRGB(Theme.background, style: .dark)
        XCTAssertNotEqual(light.red, dark.red, "라이트/다크 모드에서 배경색이 달라야 합니다")
    }

    func test_statColors_areDistinctInLightMode() {
        let colors = [Theme.statStrength, Theme.statVitality, Theme.statDiscipline, Theme.statRecovery]
        let resolved = colors.map { rgb in resolvedRGB(rgb, style: .light) }
        let unique = Set(resolved.map { "\($0.red)-\($0.green)-\($0.blue)" })
        XCTAssertEqual(unique.count, colors.count, "스탯 4종 컬러는 서로 구분되어야 합니다")
    }
}
