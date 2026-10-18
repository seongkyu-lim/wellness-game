import Foundation
import os

/// iPhone 앱과 Watch 앱이 같은 서버를 보도록 API 주소를 한곳에서 정한다.
///
/// 주소는 빌드 설정 `API_BASE_URL`을 Info.plist의 같은 키로 주입해 읽는다.
/// - Debug: 기본값은 로컬 개발 서버(`http://127.0.0.1:8080`). http를 허용한다(ATS 예외도 Debug에만 있다).
/// - Release: https만 허용한다. 비어 있거나 http·잘못된 값이면 시작 시 로그(Debug 빌드면 assert)로 알리고,
///   요청이 어디로도 나가지 않는 주소(`https://invalid.invalid`)를 쓴다.
enum ServerEnvironment {
    static let infoPlistKey = "API_BASE_URL"
    /// Debug에서 설정이 없을 때 쓰는 로컬 개발 서버. 시뮬레이터에서는 Mac의 localhost로 연결된다.
    static let localDevelopmentURL = URL(string: "http://127.0.0.1:8080")!
    /// Release 설정이 잘못됐을 때 쓰는 주소. `.invalid` TLD는 해석되지 않으므로 요청이 실패한다.
    static let unconfiguredURL = URL(string: "https://invalid.invalid")!

    enum Issue: Equatable {
        case missing
        case invalid(String)
        case insecure(String)
    }

    struct Resolution: Equatable {
        let url: URL
        let issue: Issue?
    }

    #if DEBUG
    static let allowsInsecureHTTP = true
    #else
    static let allowsInsecureHTTP = false
    #endif

    private static let logger = Logger(subsystem: "com.example.WellnessGame", category: "ServerEnvironment")

    static let current: Resolution = resolve(
        Bundle.main.object(forInfoDictionaryKey: infoPlistKey) as? String,
        allowsInsecureHTTP: allowsInsecureHTTP
    )

    static var defaultBaseURL: URL {
        current.url
    }

    /// 설정 문자열을 서버 주소로 바꾼다. 빌드 구성과 무관하게 테스트할 수 있도록 순수 함수로 둔다.
    static func resolve(_ rawValue: String?, allowsInsecureHTTP: Bool) -> Resolution {
        let fallback = allowsInsecureHTTP ? localDevelopmentURL : unconfiguredURL
        let value = rawValue?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        guard !value.isEmpty else {
            return Resolution(url: fallback, issue: allowsInsecureHTTP ? nil : .missing)
        }
        guard let url = URL(string: value), let scheme = url.scheme?.lowercased(), url.host?.isEmpty == false,
              scheme == "https" || scheme == "http" else {
            return Resolution(url: fallback, issue: .invalid(value))
        }
        guard scheme == "https" || allowsInsecureHTTP else {
            return Resolution(url: fallback, issue: .insecure(value))
        }
        return Resolution(url: url, issue: nil)
    }

    /// 앱 시작 시 호출한다. 설정 문제가 있으면 로그로 남기고 Debug 빌드에서는 멈춘다.
    static func validateAtLaunch() {
        guard let issue = current.issue else { return }
        logger.fault("API_BASE_URL 설정 오류: \(String(describing: issue), privacy: .public). https 주소를 설정하세요.")
        assertionFailure("API_BASE_URL 설정 오류: \(issue)")
    }
}
