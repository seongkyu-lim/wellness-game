import XCTest

private final class BundleToken {}

extension Bundle {
    /// 테스트 번들에 컴파일된 `<lang>.lproj`. 기기 언어와 무관하게 언어별 번역을 검증할 때 쓴다.
    static func localized(_ language: String) -> Bundle {
        let host = Bundle(for: BundleToken.self)
        guard let path = host.path(forResource: language, ofType: "lproj"),
              let bundle = Bundle(path: path) else {
            preconditionFailure("\(language).lproj 번들을 찾을 수 없습니다. Localizable.xcstrings가 테스트 타깃에 포함됐는지 확인하세요.")
        }
        return bundle
    }
}

final class LocalizationTests: XCTestCase {
    /// 화면에 직접 보이는 주요 문구(소스 언어 ko 키).
    private let keys = [
        "로그인", "로그아웃", "회원가입", "계정", "오늘의 퀘스트", "걸음 수", "수면", "획득 내역",
        "새싹이가 기다리고 있어요", "로그인하고 새싹이를 키워 보세요",
        "근력", "활력", "절제", "회복",
        "수영", "달리기", "걷기", "자전거", "근력 운동", "운동",
        "서버 응답을 확인하지 못했습니다.", "알 수 없는 오류",
        "건강 데이터 읽기 권한이 필요합니다. 설정 앱에서 권한을 확인해 주세요.",
        "로그인이 만료되었어요. 다시 로그인해 주세요.",
    ]

    func test_keyStrings_haveNonEmptyKoreanAndEnglishTranslations() {
        for language in ["ko", "en"] {
            let bundle = Bundle.localized(language)
            for key in keys {
                let value = bundle.localizedString(forKey: key, value: nil, table: nil)
                XCTAssertFalse(value.isEmpty, "\(language): '\(key)' 번역이 비어 있습니다")
            }
        }
    }

    func test_englishTranslations_differFromKoreanSource() {
        let en = Bundle.localized("en")
        for key in keys {
            let value = en.localizedString(forKey: key, value: nil, table: nil)
            XCTAssertNotEqual(value, key, "en: '\(key)'이 번역되지 않았습니다")
        }
    }

    func test_koreanTranslations_keepSourceText() {
        let ko = Bundle.localized("ko")
        for key in keys {
            XCTAssertEqual(ko.localizedString(forKey: key, value: nil, table: nil), key)
        }
    }

    /// iOS·Watch·위젯이 함께 쓰는 `Shared` 테이블(Shared/Resources/Shared.xcstrings).
    func test_sharedTable_translatesGrowthStagesAndAvatarLabel() {
        let en = Bundle.localized("en")
        let ko = Bundle.localized("ko")
        for key in ["씨앗", "새싹", "줄기", "어린나무", "나무", "개화"] {
            XCTAssertEqual(ko.localizedString(forKey: key, value: nil, table: "Shared"), key)
            XCTAssertNotEqual(en.localizedString(forKey: key, value: nil, table: "Shared"), key, "en: '\(key)'이 번역되지 않았습니다")
        }
        let label = en.localizedString(forKey: "%@ 단계 캐릭터", value: nil, table: "Shared")
        XCTAssertEqual(String(format: label, "Sprout"), "Character, Sprout stage")
    }

    func test_formatStrings_keepPlaceholdersInEnglish() {
        let en = Bundle.localized("en")
        let value = en.localizedString(forKey: "Lv.%lld · %@ 단계", value: nil, table: nil)
        XCTAssertEqual(String(format: value, 3, "Sprout"), "Lv.3 · Sprout stage")
    }
}

/// 코드에 쓴 지역화 리터럴 키가 String Catalog에 모두 있는지 검사한다.
///
/// 테스트 번들에는 소스가 없으므로 `#filePath`(이 파일의 빌드 시점 경로)를 기준으로 `ios/` 소스 디렉터리를 직접 스캔한다.
/// 대상은 iOS 앱 타깃에 컴파일되는 `WellnessGame/`과 `Shared/`. Watch·위젯 타깃은 자체 카탈로그를 쓰므로 범위에서 뺐다.
///
/// 검사하는 리터럴(첫 인자가 문자열 리터럴인 경우만):
/// - `String(localized: "…")` — `table: "Shared"`가 붙으면 Shared 카탈로그, 아니면 Localizable 카탈로그
/// - `Text("…")`, `Button("…")`, `Label("…", …)`, `.accessibilityLabel("…")`, `.navigationTitle("…")` — 모두 `LocalizedStringKey`
/// `Text(verbatim:)`나 변수 인자, `SectionHeader(title:)` 같은 커스텀 뷰 인자는 리터럴 타입을 소스만으로 알 수 없어 제외했다
/// (오탐 방지). 보간(`\(…)`)만 있고 글자가 없는 리터럴도 번역 대상이 아니라 제외한다.
final class LocalizationCatalogCoverageTests: XCTestCase {
    private static let iosRoot = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent() // WellnessGameTests
        .deletingLastPathComponent() // ios

    private static let sourceDirectories = ["WellnessGame", "Shared"]
    private static let catalogs = [
        "Localizable": "WellnessGame/Resources/Localizable.xcstrings",
        "Shared": "Shared/Resources/Shared.xcstrings",
    ]

    func test_everyLocalizedLiteralInCode_existsInStringCatalog() throws {
        let catalogKeys = try Self.catalogs.mapValues { try CatalogKeyScanner.keys(ofCatalogAt: Self.iosRoot.appending(path: $0)) }
        let usages = try Self.sourceDirectories.flatMap { try CatalogKeyScanner.usages(inDirectory: Self.iosRoot.appending(path: $0)) }

        XCTAssertGreaterThan(usages.count, 50, "소스 스캔 결과가 비정상적으로 적습니다. 경로(\(Self.iosRoot.path))를 확인하세요.")
        for usage in usages {
            let keys = try XCTUnwrap(catalogKeys[usage.table], "\(usage.location): 알 수 없는 테이블 '\(usage.table)'")
            XCTAssertTrue(
                keys.contains(usage.normalizedKey),
                "\(usage.location): '\(usage.rawKey)'가 \(usage.table).xcstrings에 없습니다"
            )
        }
    }

    // MARK: - 스캐너 자체 검증

    func test_scanner_normalizesInterpolationAndEscapes() {
        let source = #"""
        let a = String(localized: "Lv.\(level) · \(stage.displayName ?? "") 단계")
        let b = Text("한 줄\n두 줄")
        let c = String(localized: "\(name) 단계 캐릭터", table: "Shared")
        let d = Text(verbatim: "무시")
        let e = Text("\(value)")
        let f = Button("로그아웃") {}
        """#
        let usages = CatalogKeyScanner.usages(in: source, file: "Sample.swift")
        XCTAssertEqual(usages.map(\.normalizedKey), [
            CatalogKeyScanner.normalizeCatalogKey("Lv.%lld · %@ 단계"),
            "한 줄\n두 줄",
            CatalogKeyScanner.normalizeCatalogKey("%@ 단계 캐릭터"),
            "로그아웃",
        ])
        XCTAssertEqual(usages.map(\.table), ["Localizable", "Localizable", "Shared", "Localizable"])
    }

    func test_scanner_catalogKeyNormalization_treatsAllSpecifiersAlike() {
        let normalized = CatalogKeyScanner.normalizeCatalogKey("%lld분 · %@ · %1$@ · %.1f · 100%%")
        let placeholder = CatalogKeyScanner.placeholder
        XCTAssertEqual(normalized, "\(placeholder)분 · \(placeholder) · \(placeholder) · \(placeholder) · 100%")
    }
}

/// Swift 소스에서 지역화 리터럴을 뽑고, 카탈로그 키와 같은 형식으로 정규화한다.
enum CatalogKeyScanner {
    struct Usage {
        let rawKey: String
        let normalizedKey: String
        let table: String
        let location: String
    }

    /// 보간(`\(…)`)과 포맷 지정자(`%lld`, `%@` 등)를 모두 이 문자로 바꿔 비교한다.
    /// 보간 값의 타입(Int→%lld, String→%@)은 소스만으로 알 수 없어 지정자 종류는 구분하지 않는다.
    static let placeholder = "\u{FFFC}"

    // 첫 인자가 문자열 리터럴인 호출만 잡는다. 여는 따옴표까지 포함한다.
    private static let callPattern = try! NSRegularExpression(
        pattern: #"(?:String\(localized:\s*|\bText\(|\bButton\(|\bLabel\(|\.accessibilityLabel\(|\.navigationTitle\()""#
    )
    private static let tablePattern = try! NSRegularExpression(pattern: #"^\s*,\s*table:\s*"(\w+)""#)
    private static let specifierPattern = try! NSRegularExpression(
        pattern: #"%(?:\d+\$)?(?:\.\d+)?(?:lld|llu|ld|lu|li|lf|d|u|i|f|@)"#
    )

    static func keys(ofCatalogAt url: URL) throws -> Set<String> {
        let data = try Data(contentsOf: url)
        let json = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
        let strings = try XCTUnwrap(json["strings"] as? [String: Any], "\(url.lastPathComponent)에 strings가 없습니다")
        return Set(strings.keys.map(normalizeCatalogKey))
    }

    static func usages(inDirectory directory: URL) throws -> [Usage] {
        guard let enumerator = FileManager.default.enumerator(at: directory, includingPropertiesForKeys: nil) else {
            throw CocoaError(.fileReadNoSuchFile, userInfo: [NSFilePathErrorKey: directory.path])
        }
        var result: [Usage] = []
        for case let url as URL in enumerator where url.pathExtension == "swift" {
            let source = try String(contentsOf: url, encoding: .utf8)
            result += usages(in: source, file: url.lastPathComponent)
        }
        return result
    }

    static func usages(in source: String, file: String) -> [Usage] {
        let chars = Array(source)
        // NSRegularExpression은 UTF-16 오프셋을 주므로 Character 인덱스로 바꿀 수 있게 매핑한다.
        var utf16ToChar: [Int: Int] = [:]
        var offset = 0
        for (index, ch) in chars.enumerated() {
            utf16ToChar[offset] = index
            offset += ch.utf16.count
        }
        utf16ToChar[offset] = chars.count

        let nsSource = source as NSString
        var result: [Usage] = []
        for match in callPattern.matches(in: source, range: NSRange(location: 0, length: nsSource.length)) {
            guard let quote = utf16ToChar[match.range.location + match.range.length - 1],
                  case let (raw, key, end)? = parseLiteral(chars, from: quote),
                  !key.replacingOccurrences(of: placeholder, with: "").trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            else { continue }
            let rest = String(chars[end..<min(chars.count, end + 120)])
            let table = tablePattern.firstMatch(in: rest, range: NSRange(location: 0, length: (rest as NSString).length))
                .map { (rest as NSString).substring(with: $0.range(at: 1)) } ?? "Localizable"
            let line = chars[..<quote].filter { $0 == "\n" }.count + 1
            result.append(Usage(rawKey: raw, normalizedKey: key, table: table, location: "\(file):\(line)"))
        }
        return result
    }

    static func normalizeCatalogKey(_ key: String) -> String {
        let ns = key as NSString
        let replaced = specifierPattern.stringByReplacingMatches(
            in: key, range: NSRange(location: 0, length: ns.length), withTemplate: placeholder
        )
        return replaced.replacingOccurrences(of: "%%", with: "%")
    }

    /// `chars[start]`의 여는 따옴표부터 문자열 리터럴을 읽어 (소스 원문, 정규화 키, 닫는 따옴표 다음 인덱스)를 돌려준다.
    /// 보간 안의 괄호·중첩 문자열 리터럴(`?? ""`)을 건너뛴다. 여러 줄 리터럴(`"""`)은 지역화 키로 쓰지 않아 다루지 않는다.
    private static func parseLiteral(_ chars: [Character], from start: Int) -> (String, String, Int)? {
        guard start < chars.count, chars[start] == "\"" else { return nil }
        if start + 2 < chars.count, chars[start + 1] == "\"", chars[start + 2] == "\"" { return nil }
        var i = start + 1
        var key = ""
        while i < chars.count {
            let ch = chars[i]
            if ch == "\"" {
                return (String(chars[start...i]), key, i + 1)
            }
            if ch == "\n" { return nil }
            if ch == "\\", i + 1 < chars.count {
                let next = chars[i + 1]
                if next == "(" {
                    guard let close = skipInterpolation(chars, from: i + 2) else { return nil }
                    key += placeholder
                    i = close
                    continue
                }
                switch next {
                case "n": key += "\n"
                case "t": key += "\t"
                case "0": key += "\0"
                default: key.append(next) // \" \\ \'
                }
                i += 2
                continue
            }
            key.append(ch)
            i += 1
        }
        return nil
    }

    /// `\(` 다음 위치에서 시작해 짝이 맞는 `)` 다음 인덱스를 돌려준다.
    private static func skipInterpolation(_ chars: [Character], from start: Int) -> Int? {
        var depth = 1
        var i = start
        while i < chars.count {
            switch chars[i] {
            case "\"":
                guard case let (_, _, end)? = parseLiteral(chars, from: i) else { return nil }
                i = end
                continue
            case "(": depth += 1
            case ")":
                depth -= 1
                if depth == 0 { return i + 1 }
            default: break
            }
            i += 1
        }
        return nil
    }
}
