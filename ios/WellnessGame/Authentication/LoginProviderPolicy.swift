import Foundation

/// 지역에 따라 로그인 수단의 노출 순서를 정한다. UX 분기일 뿐이며 서버는 모든 provider를 받는다.
///
/// - 한국(KR): 카카오, 네이버, 구글, Apple을 모두 우선 노출한다.
/// - 그 외·알 수 없음: Apple, 구글을 우선 노출하고 카카오, 네이버는 접힌 목록에 둔다.
/// - Apple 로그인은 App Store 심사 규정(4.8)상 모든 지역에서 제공한다.
/// - ID/비밀번호는 별도 폼으로 항상 제공하므로 이 목록에 포함하지 않는다.
struct LoginProviderPolicy: Equatable {
    /// 처음부터 보이는 소셜 로그인 수단(표시 순서).
    let primary: [LoginProvider]
    /// "다른 방법으로 로그인"을 눌러야 보이는 수단(표시 순서).
    let secondary: [LoginProvider]

    static let socialProviders: [LoginProvider] = [.kakao, .naver, .google, .apple]

    init(region: Locale.Region? = Locale.current.region) {
        let primary: [LoginProvider] = region == .southKorea
            ? [.kakao, .naver, .google, .apple]
            : [.apple, .google]
        self.primary = primary
        self.secondary = Self.socialProviders.filter { !primary.contains($0) }
    }
}
