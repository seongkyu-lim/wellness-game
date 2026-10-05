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
        "로그인", "로그아웃", "회원가입", "계정", "오늘의 활동", "걸음 수", "수면", "획득 내역",
        "새싹이가 기다리고 있어요", "로그인하고 새싹이를 키워 보세요",
        "근력", "활력", "절제", "회복",
        "씨앗", "새싹", "줄기", "어린나무", "나무", "개화",
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

    func test_formatStrings_keepPlaceholdersInEnglish() {
        let en = Bundle.localized("en")
        let value = en.localizedString(forKey: "Lv.%lld · %@ 단계", value: nil, table: nil)
        XCTAssertEqual(String(format: value, 3, "Sprout"), "Lv.3 · Sprout")
    }
}
