import AuthenticationServices
import CryptoKit
import Foundation
import Security

/// 소셜 로그인 도중 사용자가 취소했음을 나타낸다. 오류 메시지 없이 조용히 돌아간다.
enum SocialLoginError: Error, Equatable {
    case cancelled
    case missingToken
}

/// Kakao·Naver SDK 로그인을 감싼 추상화. 테스트에서는 SDK 없이 가짜 구현을 주입한다.
@MainActor
protocol SocialAccessTokenProviding {
    /// 사용자 로그인을 진행하고 SDK 토큰을 돌려준다. 취소하면 `SocialLoginError.cancelled`를 던진다.
    func fetchTokens() async throws -> SocialTokens
}

/// SDK가 발급한 토큰. Naver만 refresh token이 필요하다.
struct SocialTokens: Equatable {
    let accessToken: String
    let refreshToken: String?
}

/// Sign in with Apple 인증 결과 중 서버가 쓰는 값.
struct AppleCredential: Equatable {
    static let maxFullNameLength = 100

    let identityToken: String
    /// Apple은 최초 인증에만 이름을 주므로 보통 이후에는 nil이다.
    let fullName: String?
    /// Apple 사용자 식별자(`credential.user`). 임시 보관한 이름이 같은 사용자 것인지 확인하는 데 쓴다.
    let userIdentifier: String?

    init(identityToken: String, fullName: String?, userIdentifier: String? = nil) {
        self.identityToken = identityToken
        self.userIdentifier = userIdentifier
        let trimmed = fullName?.trimmingCharacters(in: .whitespacesAndNewlines)
        // 서버 제한(100자)에 맞춰 자른다.
        self.fullName = trimmed?.isEmpty == false ? String(trimmed!.prefix(Self.maxFullNameLength)) : nil
    }

    /// `ASAuthorization`에서 identity token(UTF-8)과 이름을 꺼낸다.
    init(authorization: ASAuthorization) throws {
        guard
            let credential = authorization.credential as? ASAuthorizationAppleIDCredential,
            let tokenData = credential.identityToken,
            let token = String(data: tokenData, encoding: .utf8)
        else {
            throw SocialLoginError.missingToken
        }
        let name = credential.fullName.map { PersonNameComponentsFormatter().string(from: $0) }
        self.init(identityToken: token, fullName: name, userIdentifier: credential.user)
    }
}

/// Sign in with Apple nonce. 원문은 서버에 보내고, SHA-256 hex는 `ASAuthorizationAppleIDRequest.nonce`에 넣는다.
/// 서버는 identity token의 `nonce` 클레임이 원문의 해시와 같은지 확인해 토큰 재사용을 막는다.
enum AppleNonce {
    /// 32바이트 무작위 값을 base64url(패딩 없음, 43자)로 만든다.
    static func make(byteCount: Int = 32) -> String {
        var bytes = [UInt8](repeating: 0, count: byteCount)
        let status = SecRandomCopyBytes(kSecRandomDefault, byteCount, &bytes)
        if status != errSecSuccess {
            // 시스템 난수 실패는 사실상 없지만, 실패해도 예측 가능한 값을 쓰지 않는다.
            var generator = SystemRandomNumberGenerator()
            bytes = (0..<byteCount).map { _ in UInt8.random(in: .min ... .max, using: &generator) }
        }
        return Data(bytes).base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    static func sha256Hex(_ value: String) -> String {
        SHA256.hash(data: Data(value.utf8)).map { String(format: "%02x", $0) }.joined()
    }
}

/// Apple 최초 인증에서 받은 이름을 서버 교환이 성공할 때까지 Keychain에 임시 보관한다(#64).
/// Apple은 이름을 첫 인증에만 주므로, 교환이 실패하면 다음 로그인에서 다시 보낼 수 없게 된다.
struct PendingAppleNameStore: Sendable {
    static let account = "pendingAppleFullName"

    private struct Entry: Codable {
        let userIdentifier: String?
        let fullName: String
    }

    private let backing: AccessTokenStore

    init(backing: AccessTokenStore = KeychainTokenStore(account: PendingAppleNameStore.account)) {
        self.backing = backing
    }

    /// 같은 Apple 사용자에 대해 보관된 이름. 다른 사용자 것이면 nil.
    func fullName(for userIdentifier: String?) -> String? {
        guard let entry = load(), entry.userIdentifier == userIdentifier else { return nil }
        return entry.fullName
    }

    func save(_ fullName: String, for userIdentifier: String?) {
        guard let data = try? JSONEncoder().encode(Entry(userIdentifier: userIdentifier, fullName: fullName)),
              let json = String(data: data, encoding: .utf8) else { return }
        try? backing.saveToken(json)
    }

    /// 같은 사용자의 이름만 지운다(다른 사용자가 로그인했다고 남의 보관 이름을 버리지 않는다).
    func remove(for userIdentifier: String?) {
        guard let entry = load(), entry.userIdentifier == userIdentifier else { return }
        backing.deleteToken()
    }

    private func load() -> Entry? {
        guard let json = backing.loadToken() else { return nil }
        return try? JSONDecoder().decode(Entry.self, from: Data(json.utf8))
    }
}

/// 소셜 SDK 결과를 서버 토큰으로 교환하고 `UserSession`에 저장한다. SDK에 의존하지 않아 테스트할 수 있다.
@MainActor
struct SocialSignInFlow {
    let networkClient: NetworkClient
    var pendingAppleNames = PendingAppleNameStore()

    func signInWithKakao(tokenProvider: SocialAccessTokenProviding, session: UserSession) async {
        await run(.kakao, session: session) {
            let tokens = try await tokenProvider.fetchTokens()
            return try await networkClient.signInWithKakao(accessToken: tokens.accessToken)
        }
    }

    func signInWithNaver(tokenProvider: SocialAccessTokenProviding, session: UserSession) async {
        await run(.naver, session: session) {
            let tokens = try await tokenProvider.fetchTokens()
            // 둘 중 하나라도 비어 있으면 서버를 호출하지 않는다.
            guard !tokens.accessToken.isEmpty, let refreshToken = tokens.refreshToken, !refreshToken.isEmpty else {
                throw SocialLoginError.missingToken
            }
            return try await networkClient.signInWithNaver(accessToken: tokens.accessToken, refreshToken: refreshToken)
        }
    }

    /// `SignInWithAppleButton`의 완료 결과를 처리한다. 취소는 조용히 무시한다.
    /// - Parameter nonce: Apple 요청에 해시로 넣은 nonce 원문.
    func signInWithApple(result: Result<ASAuthorization, Error>, nonce: String?, session: UserSession) async {
        let credential: AppleCredential
        do {
            switch result {
            case let .success(authorization):
                credential = try AppleCredential(authorization: authorization)
            case let .failure(error):
                if (error as? ASAuthorizationError)?.code == .canceled {
                    throw SocialLoginError.cancelled
                }
                throw error
            }
        } catch {
            await run(.apple, session: session) { throw error }
            return
        }
        await signInWithApple(credential: credential, nonce: nonce, session: session)
    }

    /// Apple이 이번에 이름을 주지 않았으면 이전에 교환 실패로 보관한 이름을 함께 보낸다.
    /// 교환이 실패하면 이름을 보관하고, 성공하면 지운다.
    func signInWithApple(credential: AppleCredential, nonce: String?, session: UserSession) async {
        let user = credential.userIdentifier
        let fullName = credential.fullName ?? pendingAppleNames.fullName(for: user)
        await run(.apple, session: session) {
            do {
                let auth = try await networkClient.signInWithApple(
                    identityToken: credential.identityToken,
                    fullName: fullName,
                    nonce: nonce
                )
                pendingAppleNames.remove(for: user)
                return auth
            } catch {
                if let fullName {
                    pendingAppleNames.save(fullName, for: user)
                }
                throw error
            }
        }
    }

    /// 로그아웃: 서버에 이 기기 세션 폐기를 요청하고(실패해도 진행), 공급자 SDK 세션과 로컬 세션을 지운다.
    func signOut(session: UserSession, providerSignOut: (LoginProvider?) -> Void) async {
        let provider = session.provider
        if session.isSignedIn {
            try? await networkClient.logOut()
        }
        providerSignOut(provider)
        session.signOut()
    }

    private func run(
        _ provider: LoginProvider,
        session: UserSession,
        _ request: () async throws -> AuthTokenResponse
    ) async {
        session.updateStatus(String(localized: "\(provider.displayName) 로그인을 진행하고 있습니다."))
        do {
            let auth = try await request()
            try session.signIn(provider: provider, auth: auth)
        } catch SocialLoginError.cancelled {
            session.updateStatus(UserSession.signInPrompt)
        } catch SocialLoginError.missingToken {
            session.updateStatus(String(localized: "\(provider.displayName) 사용자 정보를 확인하지 못했습니다."))
        } catch {
            let message = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
            session.updateStatus(String(localized: "\(provider.displayName) 로그인에 실패했습니다: \(message)"))
        }
    }
}
