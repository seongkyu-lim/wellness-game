import Foundation

/// 서버와 주고받는 언어 코드. 서버는 ko·en만 번역하므로 그 둘로 좁힌다.
enum AppLanguage {
    static let supported: Set<String> = ["ko", "en"]
    /// 지원 언어가 하나도 없을 때 쓰는 개발 언어.
    static let fallback = "en"

    /// `Accept-Language` 값. 기기 선호 언어 순서대로 처음 만나는 ko/en을 쓰고, 둘 다 없으면 en으로 대체한다.
    static func acceptLanguage(preferredLanguages: [String] = Locale.preferredLanguages) -> String {
        preferredLanguages.lazy.compactMap(normalized).first ?? fallback
    }

    /// 언어 식별자(`ko-KR`, `en_US` 등)를 지원 언어 코드로 바꾼다. 지원하지 않으면 nil.
    static func normalized(_ identifier: String?) -> String? {
        guard let identifier, !identifier.isEmpty,
              let code = Locale(identifier: identifier).language.languageCode?.identifier,
              supported.contains(code) else {
            return nil
        }
        return code
    }
}
