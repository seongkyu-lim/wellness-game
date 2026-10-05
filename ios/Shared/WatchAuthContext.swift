import Foundation

/// iPhone이 WatchConnectivity application context로 Watch에 넘기는 로그인 정보.
///
/// - iPhone: 로그인·세션 복원 시 `applicationContext`, 로그아웃·401 시 `signedOutContext(language:)`를 보낸다.
/// - Watch: `init?(applicationContext:)`로 해석해 Keychain에 저장하고, nil(빈 값)이면 삭제한다.
/// 딕셔너리에는 property list 호환 타입(String, Double)만 넣는다.
struct WatchAuthContext: Codable, Equatable, Sendable {
    let accessToken: String
    let userId: String
    let displayName: String?
    /// 토큰 만료 시각. 서버가 유효 기간을 주지 않았으면 nil.
    let expiresAt: Date?
    /// 서버 `Accept-Language`로 쓸 언어(ko|en).
    let language: String

    enum Key {
        static let accessToken = "accessToken"
        static let userId = "userId"
        static let displayName = "displayName"
        /// 1970 기준 초. 만료 시각이 없으면 0.
        static let expiresAt = "expiresAt"
        static let language = "language"
    }

    init(accessToken: String, userId: String, displayName: String?, expiresAt: Date?, language: String) {
        self.accessToken = accessToken
        self.userId = userId
        self.displayName = displayName
        self.expiresAt = expiresAt
        self.language = language
    }

    /// `WCSession.updateApplicationContext`로 보낼 딕셔너리.
    var applicationContext: [String: Any] {
        // Any 문맥에서 `??`가 Optional로 추론되지 않도록 타입을 고정한다.
        let name: String = displayName ?? ""
        let expiresAtSeconds: Double = expiresAt?.timeIntervalSince1970 ?? 0
        return [
            Key.accessToken: accessToken,
            Key.userId: userId,
            Key.displayName: name,
            Key.expiresAt: expiresAtSeconds,
            Key.language: language,
        ]
    }

    /// 로그아웃 상태를 알리는 빈 컨텍스트. 키는 유지하고 값만 비운다.
    static func signedOutContext(language: String) -> [String: Any] {
        [
            Key.accessToken: "",
            Key.userId: "",
            Key.displayName: "",
            Key.expiresAt: 0.0,
            Key.language: language,
        ]
    }

    /// 받은 컨텍스트를 해석한다. 토큰이나 userId가 비어 있으면(로그아웃) nil.
    init?(applicationContext context: [String: Any]) {
        guard let token = context[Key.accessToken] as? String, !token.isEmpty,
              let userId = context[Key.userId] as? String, !userId.isEmpty else {
            return nil
        }
        let name = context[Key.displayName] as? String
        let expiresAtSeconds = (context[Key.expiresAt] as? NSNumber)?.doubleValue ?? 0
        self.init(
            accessToken: token,
            userId: userId,
            displayName: name?.isEmpty == false ? name : nil,
            expiresAt: expiresAtSeconds > 0 ? Date(timeIntervalSince1970: expiresAtSeconds) : nil,
            language: AppLanguage.normalized(context[Key.language] as? String) ?? AppLanguage.fallback
        )
    }

    func isExpired(at now: Date) -> Bool {
        expiresAt.map { $0 <= now } ?? false
    }
}
