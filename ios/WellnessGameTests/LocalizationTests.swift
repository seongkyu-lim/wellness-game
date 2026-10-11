import XCTest

private final class BundleToken {}

extension Bundle {
    /// 카탈로그가 컴파일돼 들어간 테스트 번들.
    static var testResources: Bundle { Bundle(for: BundleToken.self) }

    /// 테스트 번들에 컴파일된 `<lang>.lproj`. 기기 언어와 무관하게 언어별 번역을 검증할 때 쓴다.
    static func localized(_ language: String) -> Bundle {
        guard let path = testResources.path(forResource: language, ofType: "lproj"),
              let bundle = Bundle(path: path) else {
            preconditionFailure("\(language).lproj 번들을 찾을 수 없습니다. Localizable.xcstrings가 테스트 타깃에 포함됐는지 확인하세요.")
        }
        return bundle
    }

    /// `localizedString(forKey:value:table:)` 축약.
    func string(_ key: String, table: String? = nil) -> String {
        localizedString(forKey: key, value: nil, table: table)
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
                let value = bundle.string(key)
                XCTAssertFalse(value.isEmpty, "\(language): '\(key)' 번역이 비어 있습니다")
            }
        }
    }

    func test_englishTranslations_differFromKoreanSource() {
        let en = Bundle.localized("en")
        for key in keys {
            let value = en.string(key)
            XCTAssertNotEqual(value, key, "en: '\(key)'이 번역되지 않았습니다")
        }
    }

    func test_koreanTranslations_keepSourceText() {
        let ko = Bundle.localized("ko")
        for key in keys {
            XCTAssertEqual(ko.string(key), key)
        }
    }

    /// iOS·Watch·위젯이 함께 쓰는 `Shared` 테이블(Shared/Resources/Shared.xcstrings).
    func test_sharedTable_translatesGrowthStagesAndAvatarLabel() {
        let en = Bundle.localized("en")
        let ko = Bundle.localized("ko")
        for key in ["씨앗", "새싹", "줄기", "어린나무", "나무", "개화"] {
            XCTAssertEqual(ko.string(key, table: "Shared"), key)
            XCTAssertNotEqual(en.string(key, table: "Shared"), key, "en: '\(key)'이 번역되지 않았습니다")
        }
        let label = en.string("%@ 단계 캐릭터", table: "Shared")
        XCTAssertEqual(String(format: label, "Sprout"), "Character, Sprout stage")
    }

    func test_formatStrings_keepPlaceholdersInEnglish() {
        let en = Bundle.localized("en")
        let value = en.string("Lv.%lld · %@ 단계")
        XCTAssertEqual(String(format: value, 3, "Sprout"), "Lv.3 · Sprout stage")
    }
}

/// 코드에 쓴 지역화 리터럴 키가 String Catalog에 있고 en 번역이 비어 있지 않은지 검사한다.
///
/// 테스트 번들에는 소스가 없으므로 `#filePath`(이 파일의 빌드 시점 경로)를 기준으로 `ios/` 소스 디렉터리를 직접 스캔한다.
/// 타깃별 기준 카탈로그:
/// - `WellnessGame/`(iOS 앱), `WellnessGameWatch/`(Watch 앱), `WellnessGameWatchWidgets/`(컴플리케이션)는 각자의
///   Localizable 카탈로그, `table:`/`tableName:`이 "Shared"면 Shared 카탈로그.
/// - `Shared/` 파일은 project.pbxproj에서 그 파일을 컴파일하는 타깃들을 찾아, 각 타깃의 Localizable 카탈로그 모두에서 확인한다.
///
/// 검사하는 리터럴(첫 인자가 문자열 리터럴인 경우만): `String(localized:)`, `LocalizedStringResource`,
/// `Text`·`Button`·`Label`·`Toggle`·`Section`·`Picker`·`Link`, `.accessibilityLabel`·`.navigationTitle`·
/// `.alert`·`.confirmationDialog` 제목, 위젯 `.configurationDisplayName`·`.description`.
/// `Text(verbatim:)`나 변수 인자, `SectionHeader(title:)` 같은 커스텀 뷰 인자는 리터럴이 지역화 키인지 소스만으로 알 수 없어
/// 제외했다(오탐 방지). 보간만 있고 글자가 없는 리터럴, `#Preview` 블록(출시 화면이 아님)도 제외한다.
final class LocalizationCatalogCoverageTests: XCTestCase {
    private static let iosRoot = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent() // WellnessGameTests
        .deletingLastPathComponent() // ios

    private static let sharedDirectory = "Shared"
    private static let sharedCatalog = "Shared/Resources/Shared.xcstrings"
    /// 타깃 이름(= 소스 디렉터리) → 기본 테이블(Localizable) 카탈로그.
    private static let targetCatalogs = [
        "WellnessGame": "WellnessGame/Resources/Localizable.xcstrings",
        "WellnessGameWatch": "WellnessGameWatch/Resources/Localizable.xcstrings",
        "WellnessGameWatchWidgets": "WellnessGameWatchWidgets/Localizable.xcstrings",
    ]

    /// 알려진 누락(카탈로그 경로, 원문 키). 이 브랜치 범위 밖 카탈로그라 여기서 고치지 않았다.
    /// 채워지면 테스트가 "목록에서 지우라"고 실패하므로 방치되지 않는다.
    private static let knownMissing: Set<[String]> = []

    func test_everyLocalizedLiteralInCode_existsInStringCatalogWithEnglish() throws {
        var cache: [String: [String: String]] = [:]
        func catalog(_ path: String) throws -> [String: String] {
            if let cached = cache[path] { return cached }
            let loaded = try CatalogKeyScanner.englishValues(ofCatalogAt: Self.iosRoot.appending(path: path))
            cache[path] = loaded
            return loaded
        }
        let knownMissing = Set(Self.knownMissing.map { [$0[0], CatalogKeyScanner.normalizeCatalogKey($0[1])] })
        let membership = try ProjectSourceMembership(
            pbxproj: Self.iosRoot.appending(path: "WellnessGame.xcodeproj/project.pbxproj")
        )

        var seenKnownMissing: Set<[String]> = []
        for directory in Array(Self.targetCatalogs.keys) + [Self.sharedDirectory] {
            let usages = try CatalogKeyScanner.usages(inDirectory: Self.iosRoot.appending(path: directory))
            XCTAssertFalse(usages.isEmpty, "\(directory): 소스 스캔 결과가 없습니다. 경로(\(Self.iosRoot.path))를 확인하세요.")
            for usage in usages {
                let paths: [String]
                switch usage.table {
                case "Shared":
                    paths = [Self.sharedCatalog]
                case "Localizable":
                    let targets = directory == Self.sharedDirectory ? membership.targets(compiling: usage.file) : [directory]
                    paths = targets.compactMap { Self.targetCatalogs[$0] }.sorted()
                    XCTAssertFalse(paths.isEmpty, "\(usage.location): 이 파일을 컴파일하는 앱 타깃을 찾지 못했습니다")
                default:
                    XCTFail("\(usage.location): 알 수 없는 테이블 '\(usage.table)'")
                    continue
                }
                for path in paths {
                    let english = try catalog(path)[usage.normalizedKey]
                    if knownMissing.contains([path, usage.normalizedKey]) {
                        seenKnownMissing.insert([path, usage.normalizedKey])
                        XCTAssertNil(english, "\(usage.rawKey)가 \(path)에 추가됐습니다. knownMissing에서 지우세요.")
                        continue
                    }
                    XCTAssertNotNil(english, "\(usage.location): \(usage.rawKey)가 \(path)에 없습니다")
                    if let english {
                        XCTAssertFalse(english.isEmpty, "\(usage.location): \(usage.rawKey)의 en 번역이 \(path)에서 비어 있습니다")
                    }
                }
            }
        }
        XCTAssertEqual(seenKnownMissing, knownMissing, "knownMissing에 더는 코드에 없는 항목이 있습니다")
    }

    // MARK: - 스캐너 자체 검증

    func test_scanner_normalizesInterpolationEscapesAndTables() {
        let source = #"""
        let a = String(localized: "Lv.\(level) · \(stage.displayName ?? "") 단계")
        let b = Text("한 줄\n두 줄")
        let c = String(localized: "\(name) 단계 캐릭터", table: "Shared")
        let d = Text(verbatim: "무시")
        let e = Text("\(value)")
        let f = Button("로그아웃") {}
        let g = Text("화살표 \u{2192} 끝", tableName: "Shared")
        let h: LocalizedStringResource = "리소스"
        let i = Toggle("알림", isOn: $on)
        let j = view.alert("삭제할까요?", isPresented: $shown) {}
        """#
        let usages = CatalogKeyScanner.usages(in: source, file: "Sample.swift")
        XCTAssertEqual(usages.map(\.normalizedKey), [
            CatalogKeyScanner.normalizeCatalogKey("Lv.%lld · %@ 단계"),
            "한 줄\n두 줄",
            CatalogKeyScanner.normalizeCatalogKey("%@ 단계 캐릭터"),
            "로그아웃",
            "화살표 → 끝",
            "리소스",
            "알림",
            "삭제할까요?",
        ])
        XCTAssertEqual(usages.map(\.table), [
            "Localizable", "Localizable", "Shared", "Localizable", "Shared", "Localizable", "Localizable", "Localizable",
        ])
    }

    func test_scanner_skipsPreviewBlocks() {
        let source = #"""
        let a = Text("출시 문구")
        #Preview {
            VStack { Text("미리보기 \("{")") ; Text("미리보기2") }
        }
        let b = Text("다음 문구")
        """#
        XCTAssertEqual(CatalogKeyScanner.usages(in: source, file: "Sample.swift").map(\.normalizedKey), ["출시 문구", "다음 문구"])
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
        let file: String
        let line: Int
        var location: String { "\(file):\(line)" }
    }

    /// 보간(`\(…)`)과 포맷 지정자(`%lld`, `%@` 등)를 모두 이 문자로 바꿔 비교한다.
    /// 보간 값의 타입(Int→%lld, String→%@)은 소스만으로 알 수 없어 지정자 종류는 구분하지 않는다.
    static let placeholder = "\u{FFFC}"

    // 첫 인자가 문자열 리터럴인 호출만 잡는다. 여는 따옴표까지 포함한다.
    private static let callPattern = try! NSRegularExpression(pattern: "(?:" + [
        #"String\(localized:\s*"#,
        #"LocalizedStringResource\(\s*"#,
        #":\s*LocalizedStringResource\s*=\s*"#,
        #"\b(?:Text|Button|Label|Toggle|Section|Picker|Link)\("#,
        #"\.(?:accessibilityLabel|navigationTitle|alert|confirmationDialog|configurationDisplayName|description)\("#,
    ].joined(separator: "|") + ")\"")
    // 키 다음의 같은 호출 인자 중 table:/tableName:을 찾는다(defaultValue: 등 다른 인자가 앞에 와도 한 줄 안이면 허용).
    private static let tablePattern = try! NSRegularExpression(pattern: #"^[^\n]*?\b(?:table|tableName):\s*"(\w+)""#)
    private static let specifierPattern = try! NSRegularExpression(
        pattern: #"%(?:\d+\$)?(?:\.\d+)?(?:lld|llu|ld|lu|li|lf|d|u|i|f|@)"#
    )

    /// 카탈로그의 (정규화 키 → en 값). en 값은 stringUnit 값, 복수형 등 variations만 있으면 "variations"로 표시한다.
    static func englishValues(ofCatalogAt url: URL) throws -> [String: String] {
        let data = try Data(contentsOf: url)
        let json = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
        let strings = try XCTUnwrap(json["strings"] as? [String: Any], "\(url.lastPathComponent)에 strings가 없습니다")
        var result: [String: String] = [:]
        for (key, entry) in strings {
            let en = ((entry as? [String: Any])?["localizations"] as? [String: Any])?["en"] as? [String: Any]
            let value = (en?["stringUnit"] as? [String: Any])?["value"] as? String
            result[normalizeCatalogKey(key)] = value ?? (en?["variations"] != nil ? "variations" : "")
        }
        return result
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

        let previews = previewRanges(chars)
        var result: [Usage] = []
        for match in callPattern.matches(in: source, range: NSRange(location: 0, length: (source as NSString).length)) {
            guard let quote = utf16ToChar[match.range.location + match.range.length - 1],
                  !previews.contains(where: { $0.contains(quote) }),
                  case let (raw, key, end)? = parseLiteral(chars, from: quote),
                  !key.replacingOccurrences(of: placeholder, with: "").trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            else { continue }
            let rest = String(chars[end..<min(chars.count, end + 160)]) as NSString
            let table = tablePattern.firstMatch(in: rest as String, range: NSRange(location: 0, length: rest.length))
                .map { rest.substring(with: $0.range(at: 1)) } ?? "Localizable"
            let line = chars[..<quote].filter { $0 == "\n" }.count + 1
            result.append(Usage(rawKey: raw, normalizedKey: key, table: table, file: file, line: line))
        }
        return result
    }

    static func normalizeCatalogKey(_ key: String) -> String {
        let replaced = specifierPattern.stringByReplacingMatches(
            in: key, range: NSRange(location: 0, length: (key as NSString).length), withTemplate: placeholder
        )
        return replaced.replacingOccurrences(of: "%%", with: "%")
    }

    /// `#Preview { … }` 블록의 문자 범위들. 블록 안 문자열 리터럴의 중괄호는 세지 않는다.
    private static func previewRanges(_ chars: [Character]) -> [Range<Int>] {
        let marker = Array("#Preview")
        var ranges: [Range<Int>] = []
        var i = 0
        while i + marker.count <= chars.count {
            guard Array(chars[i..<i + marker.count]) == marker else { i += 1; continue }
            var j = i + marker.count
            var depth = 0
            var opened = false
            while j < chars.count {
                let ch = chars[j]
                if ch == "\"", case let (_, _, end)? = parseLiteral(chars, from: j) {
                    j = end
                    continue
                }
                if ch == "{" { depth += 1; opened = true }
                if ch == "}" {
                    depth -= 1
                    if opened && depth == 0 { j += 1; break }
                }
                j += 1
            }
            ranges.append(i..<j)
            i = j
        }
        return ranges
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
                switch next {
                case "(":
                    guard let close = skipInterpolation(chars, from: i + 2) else { return nil }
                    key += placeholder
                    i = close
                    continue
                case "u":
                    // \u{XXXX} → 해당 유니코드 스칼라
                    guard i + 2 < chars.count, chars[i + 2] == "{",
                          let close = chars[(i + 3)...].firstIndex(of: "}"),
                          let code = UInt32(String(chars[(i + 3)..<close]), radix: 16),
                          let scalar = Unicode.Scalar(code)
                    else { return nil }
                    key.unicodeScalars.append(scalar)
                    i = close + 1
                    continue
                case "n": key += "\n"
                case "t": key += "\t"
                case "r": key += "\r"
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

/// project.pbxproj에서 타깃별로 컴파일되는 소스 파일 이름을 읽는다(Shared 파일이 어느 타깃에 들어가는지 확인용).
/// 타깃 → Sources 빌드 단계 → PBXBuildFile → PBXFileReference(path) 순으로 ID를 따라간다. 주석 이름은 믿지 않는다.
struct ProjectSourceMembership {
    private var filesByTarget: [String: Set<String>] = [:]

    init(pbxproj url: URL) throws {
        let text = try String(contentsOf: url, encoding: .utf8)
        func matches(_ pattern: String) -> [[String]] {
            let regex = try! NSRegularExpression(pattern: pattern, options: [.dotMatchesLineSeparators])
            let ns = text as NSString
            return regex.matches(in: text, range: NSRange(location: 0, length: ns.length)).map { match in
                (1..<match.numberOfRanges).map { ns.substring(with: match.range(at: $0)) }
            }
        }
        let id = #"\b([0-9A-F]{16,})\b"#  // 손으로 만든 ID는 24자가 아닌 경우가 있다
        let fileRefPaths = Dictionary(
            matches(id + #" /\*[^*]*\*/ = \{isa = PBXFileReference;[^}]*?path = "?([^";]+)"?;"#).map { ($0[0], $0[1]) },
            uniquingKeysWith: { first, _ in first }
        )
        let buildFileRefs = Dictionary(
            matches(id + #" /\*[^*]*\*/ = \{isa = PBXBuildFile; fileRef = "# + id).map { ($0[0], $0[1]) },
            uniquingKeysWith: { first, _ in first }
        )
        let sourcesPhases = Dictionary(
            matches(id + #" /\* Sources \*/ = \{\s*isa = PBXSourcesBuildPhase;.*?files = \((.*?)\);"#).map { ($0[0], $0[1]) },
            uniquingKeysWith: { first, _ in first }
        )
        let idPattern = try! NSRegularExpression(pattern: id)
        func ids(in list: String) -> [String] {
            let ns = list as NSString
            return idPattern.matches(in: list, range: NSRange(location: 0, length: ns.length)).map { ns.substring(with: $0.range) }
        }
        for target in matches(id + #" /\* (\w+) \*/ = \{\s*isa = PBXNativeTarget;.*?buildPhases = \((.*?)\);"#) {
            let files = ids(in: target[2])
                .compactMap { sourcesPhases[$0] }
                .flatMap(ids(in:))
                .compactMap { buildFileRefs[$0].flatMap { fileRefPaths[$0] } }
                .map { ($0 as NSString).lastPathComponent }
            filesByTarget[target[1]] = Set(files)
        }
        XCTAssertFalse(filesByTarget.isEmpty, "project.pbxproj에서 타깃을 읽지 못했습니다")
    }

    func targets(compiling fileName: String) -> [String] {
        filesByTarget.filter { $0.value.contains(fileName) }.map(\.key)
    }
}
