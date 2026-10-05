import XCTest

final class AppLanguageTests: XCTestCase {
    func test_acceptLanguage_picksFirstSupportedLanguage() {
        XCTAssertEqual(AppLanguage.acceptLanguage(preferredLanguages: ["ko-KR", "en-US"]), "ko")
        XCTAssertEqual(AppLanguage.acceptLanguage(preferredLanguages: ["en-GB", "ko-KR"]), "en")
        XCTAssertEqual(AppLanguage.acceptLanguage(preferredLanguages: ["ja-JP", "ko-KR"]), "ko")
        XCTAssertEqual(AppLanguage.acceptLanguage(preferredLanguages: ["zh-Hans-CN"]), "en")
        XCTAssertEqual(AppLanguage.acceptLanguage(preferredLanguages: []), "en")
    }

    func test_normalized_mapsIdentifiersToSupportedCodes() {
        XCTAssertEqual(AppLanguage.normalized("ko_KR"), "ko")
        XCTAssertEqual(AppLanguage.normalized("en"), "en")
        XCTAssertNil(AppLanguage.normalized("ja"))
        XCTAssertNil(AppLanguage.normalized(""))
        XCTAssertNil(AppLanguage.normalized(nil))
    }
}

final class ServerEnvironmentTests: XCTestCase {
    func test_debug_allowsLocalHTTP_andFallsBackToLocalServer() {
        XCTAssertEqual(
            ServerEnvironment.resolve("http://127.0.0.1:8080", allowsInsecureHTTP: true),
            .init(url: URL(string: "http://127.0.0.1:8080")!, issue: nil)
        )
        XCTAssertEqual(
            ServerEnvironment.resolve(nil, allowsInsecureHTTP: true),
            .init(url: ServerEnvironment.localDevelopmentURL, issue: nil)
        )
        XCTAssertEqual(
            ServerEnvironment.resolve("", allowsInsecureHTTP: true),
            .init(url: ServerEnvironment.localDevelopmentURL, issue: nil)
        )
    }

    func test_release_acceptsOnlyHTTPS() {
        XCTAssertEqual(
            ServerEnvironment.resolve(" https://api.wellnessgame.example ", allowsInsecureHTTP: false),
            .init(url: URL(string: "https://api.wellnessgame.example")!, issue: nil)
        )
        XCTAssertEqual(
            ServerEnvironment.resolve("http://api.wellnessgame.example", allowsInsecureHTTP: false),
            .init(url: ServerEnvironment.unconfiguredURL, issue: .insecure("http://api.wellnessgame.example"))
        )
    }

    func test_release_reportsMissingOrInvalidValue() {
        XCTAssertEqual(ServerEnvironment.resolve(nil, allowsInsecureHTTP: false).issue, .missing)
        XCTAssertEqual(ServerEnvironment.resolve("   ", allowsInsecureHTTP: false).issue, .missing)
        XCTAssertEqual(ServerEnvironment.resolve("$(API_BASE_URL)", allowsInsecureHTTP: false).issue, .invalid("$(API_BASE_URL)"))
        XCTAssertEqual(ServerEnvironment.resolve("ftp://example.com", allowsInsecureHTTP: true).issue, .invalid("ftp://example.com"))
        XCTAssertEqual(ServerEnvironment.resolve("ftp://example.com", allowsInsecureHTTP: false).url, ServerEnvironment.unconfiguredURL)
    }

    func test_testRunner_usesDebugLocalServer() {
        // 테스트 러너(Debug, Info.plist에 API_BASE_URL 없음)는 기존 로컬 주소를 그대로 쓴다.
        XCTAssertEqual(ServerEnvironment.defaultBaseURL, URL(string: "http://127.0.0.1:8080")!)
        XCTAssertEqual(NetworkClient().baseURL, ServerEnvironment.defaultBaseURL)
    }
}

/// 구성별 Info.plist(Release `Info.plist`, Debug `Info-Debug.plist`)가 ATS 예외 말고는 어긋나지 않는지 확인한다.
/// 시뮬레이터 테스트는 Mac 파일 시스템을 읽을 수 있으므로 `#filePath` 기준으로 원본 파일을 연다.
final class InfoPlistConfigurationTests: XCTestCase {
    private static let iosRoot = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent()
        .deletingLastPathComponent()
    private static let resourceDirectories = ["WellnessGame/Resources", "WellnessGameWatch/Resources"]
    private static let atsKey = "NSAppTransportSecurity"

    private func plist(_ path: String) throws -> [String: Any] {
        let url = Self.iosRoot.appendingPathComponent(path)
        let data = try Data(contentsOf: url)
        return try XCTUnwrap(PropertyListSerialization.propertyList(from: data, format: nil) as? [String: Any], path)
    }

    func test_debugPlist_matchesReleasePlistExceptATS() throws {
        for directory in Self.resourceDirectories {
            let release = try plist("\(directory)/Info.plist")
            var debug = try plist("\(directory)/Info-Debug.plist")

            XCTAssertNil(release[Self.atsKey], "\(directory): Release Info.plist에는 ATS 예외가 없어야 합니다")
            let ats = try XCTUnwrap(debug.removeValue(forKey: Self.atsKey) as? [String: Any], "\(directory): Debug에 ATS 예외가 필요합니다")
            XCTAssertEqual(ats as NSDictionary, ["NSAllowsLocalNetworking": true] as NSDictionary)
            XCTAssertEqual(debug as NSDictionary, release as NSDictionary, "\(directory): ATS 외의 키가 어긋났습니다")
        }
    }
}
