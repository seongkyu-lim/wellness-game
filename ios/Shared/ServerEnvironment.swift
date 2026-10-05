import Foundation

/// iPhone 앱과 Watch 앱이 같은 서버를 보도록 기본 API 주소를 한곳에 둔다.
enum ServerEnvironment {
    /// 로컬 개발 서버. 시뮬레이터에서는 Mac의 localhost로 연결된다.
    static let defaultBaseURL = URL(string: "http://127.0.0.1:8080")!
}
