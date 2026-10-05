import AuthenticationServices
import Foundation

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

    init(identityToken: String, fullName: String?) {
        self.identityToken = identityToken
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
        self.init(identityToken: token, fullName: name)
    }
}

/// 소셜 SDK 결과를 서버 토큰으로 교환하고 `UserSession`에 저장한다. SDK에 의존하지 않아 테스트할 수 있다.
@MainActor
struct SocialSignInFlow {
    let networkClient: NetworkClient

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
    func signInWithApple(result: Result<ASAuthorization, Error>, session: UserSession) async {
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
        await signInWithApple(credential: credential, session: session)
    }

    func signInWithApple(credential: AppleCredential, session: UserSession) async {
        await run(.apple, session: session) {
            try await networkClient.signInWithApple(
                identityToken: credential.identityToken,
                fullName: credential.fullName
            )
        }
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
