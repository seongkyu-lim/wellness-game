import AuthenticationServices
import Combine
import Foundation
import GoogleSignIn
import KakaoSDKAuth
import KakaoSDKCommon
import KakaoSDKUser
import NidThirdPartyLogin
import UIKit

@MainActor
final class SocialLoginService: ObservableObject {
    @Published private(set) var isLoading = false

    static func initializeKakaoIfConfigured() {
        guard let appKey = configurationValue(for: "KAKAO_NATIVE_APP_KEY") else {
            return
        }
        KakaoSDK.initSDK(appKey: appKey)
    }

    static func initializeNaverIfConfigured() {
        guard
            let appName = configurationValue(for: "NAVER_APP_NAME"),
            let clientId = configurationValue(for: "NAVER_CLIENT_ID"),
            let clientSecret = configurationValue(for: "NAVER_CLIENT_SECRET"),
            let urlScheme = configurationValue(for: "NAVER_URL_SCHEME")
        else {
            return
        }

        NidOAuth.shared.initialize(
            appName: appName,
            clientId: clientId,
            clientSecret: clientSecret,
            urlScheme: urlScheme
        )
    }

    static func handleOpenURL(_ url: URL) -> Bool {
        if GIDSignIn.sharedInstance.handle(url) {
            return true
        }
        if configurationValue(for: "KAKAO_NATIVE_APP_KEY") != nil,
           AuthApi.isKakaoTalkLoginUrl(url) {
            return AuthController.handleOpenUrl(url: url)
        }
        if isNaverConfigured {
            return NidOAuth.shared.handleURL(url)
        }
        return false
    }

    private let networkClient: NetworkClient

    init(networkClient: NetworkClient = NetworkClient()) {
        self.networkClient = networkClient
    }

    /// Google SDK 로그인 → ID 토큰을 서버(`/api/auth/google/native`)에서 서버 토큰으로 교환한다.
    /// 서버 교환에 실패하면 SDK 세션도 정리해 반쯤 로그인된 상태를 남기지 않는다.
    func signInWithGoogle(_ session: UserSession) {
        guard Self.configurationValue(for: "GIDClientID") != nil else {
            session.updateStatus(String(localized: "Google OAuth 클라이언트 ID를 먼저 설정해 주세요."))
            return
        }
        guard let presenter = Self.presentingViewController() else {
            session.updateStatus(String(localized: "Google 로그인 화면을 표시하지 못했습니다."))
            return
        }

        isLoading = true
        session.updateStatus(String(localized: "Google 로그인을 진행하고 있습니다."))
        GIDSignIn.sharedInstance.signIn(withPresenting: presenter) { [weak self] result, error in
            Task { @MainActor in
                guard let self else { return }
                if let error {
                    self.isLoading = false
                    session.updateStatus(String(localized: "Google 로그인에 실패했습니다: \(error.localizedDescription)"))
                    return
                }
                guard let idToken = result?.user.idToken?.tokenString else {
                    self.isLoading = false
                    GIDSignIn.sharedInstance.signOut()
                    session.updateStatus(String(localized: "Google 사용자 정보를 확인하지 못했습니다."))
                    return
                }
                await self.exchangeGoogleToken(idToken, session: session)
            }
        }
    }

    private func exchangeGoogleToken(_ idToken: String, session: UserSession) async {
        defer { isLoading = false }
        do {
            let auth = try await networkClient.signInWithGoogle(idToken: idToken)
            try session.signIn(provider: .google, auth: auth)
        } catch {
            GIDSignIn.sharedInstance.signOut()
            let message = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
            session.updateStatus(String(localized: "Google 로그인에 실패했습니다: \(message)"))
        }
    }

    /// Kakao SDK 로그인(카카오톡 앱 → 카카오계정 순) 후 액세스 토큰을 서버(`/api/auth/kakao/native`)에서 교환한다.
    func signInWithKakao(_ session: UserSession) {
        guard Self.configurationValue(for: "KAKAO_NATIVE_APP_KEY") != nil else {
            session.updateStatus(String(localized: "\(LoginProvider.kakao.displayName) 로그인 설정을 먼저 확인해 주세요."))
            return
        }
        perform { flow in
            await flow.signInWithKakao(tokenProvider: KakaoTokenProvider(), session: session)
        }
    }

    /// Naver SDK 로그인 후 액세스 토큰을 서버(`/api/auth/naver/native`)에서 교환한다.
    func signInWithNaver(_ session: UserSession) {
        guard Self.isNaverConfigured else {
            session.updateStatus(String(localized: "\(LoginProvider.naver.displayName) 로그인 설정을 먼저 확인해 주세요."))
            return
        }
        perform { flow in
            await flow.signInWithNaver(tokenProvider: NaverTokenProvider(), session: session)
        }
    }

    /// `SignInWithAppleButton` 완료 결과를 서버(`/api/auth/apple/native`)로 교환한다. 사용자가 취소하면 조용히 돌아간다.
    func signInWithApple(_ result: Result<ASAuthorization, Error>, session: UserSession) {
        perform { flow in
            await flow.signInWithApple(result: result, session: session)
        }
    }

    private func perform(_ work: @escaping @MainActor (SocialSignInFlow) async -> Void) {
        guard !isLoading else { return }
        isLoading = true
        let flow = SocialSignInFlow(networkClient: networkClient)
        Task { @MainActor in
            await work(flow)
            isLoading = false
        }
    }

    /// 로그아웃: SDK 세션을 정리하고 Keychain 토큰을 포함한 앱 세션을 지운다.
    func signOut(_ session: UserSession) {
        switch session.provider {
        case .google:
            GIDSignIn.sharedInstance.signOut()
        case .kakao:
            UserApi.shared.logout { _ in }
        case .naver:
            NidOAuth.shared.logout()
        case .apple, .password, nil:
            break
        }
        session.signOut()
    }

    private static var isNaverConfigured: Bool {
        configurationValue(for: "NAVER_APP_NAME") != nil
            && configurationValue(for: "NAVER_CLIENT_ID") != nil
            && configurationValue(for: "NAVER_CLIENT_SECRET") != nil
            && configurationValue(for: "NAVER_URL_SCHEME") != nil
    }

    private static func configurationValue(for key: String) -> String? {
        guard let value = Bundle.main.object(forInfoDictionaryKey: key) as? String else {
            return nil
        }
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, !trimmed.contains("$("), !trimmed.contains("REPLACE_WITH") else {
            return nil
        }
        return trimmed
    }

    private static func presentingViewController() -> UIViewController? {
        let scene = UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .first { $0.activationState == .foregroundActive }
        var presenter = scene?.windows.first { $0.isKeyWindow }?.rootViewController
        while let presented = presenter?.presentedViewController {
            presenter = presented
        }
        return presenter
    }
}

/// Kakao SDK로 로그인해 액세스 토큰을 얻는다. 카카오톡 앱이 있으면 앱 로그인, 없거나 실패하면 카카오계정 로그인.
@MainActor
struct KakaoTokenProvider: SocialAccessTokenProviding {
    func fetchAccessToken() async throws -> String {
        try await withCheckedThrowingContinuation { continuation in
            let finish: (OAuthToken?, Error?) -> Void = { token, error in
                if let error {
                    continuation.resume(throwing: Self.isCancellation(error) ? SocialLoginError.cancelled : error)
                } else if let accessToken = token?.accessToken {
                    continuation.resume(returning: accessToken)
                } else {
                    continuation.resume(throwing: SocialLoginError.missingToken)
                }
            }
            if UserApi.isKakaoTalkLoginAvailable() {
                UserApi.shared.loginWithKakaoTalk { token, error in
                    if let error, !Self.isCancellation(error) {
                        UserApi.shared.loginWithKakaoAccount(completion: finish)
                    } else {
                        finish(token, error)
                    }
                }
            } else {
                UserApi.shared.loginWithKakaoAccount(completion: finish)
            }
        }
    }

    private static func isCancellation(_ error: Error) -> Bool {
        if case SdkError.ClientFailed(.Cancelled, _) = error {
            return true
        }
        return false
    }
}

/// Naver SDK로 로그인해 액세스 토큰을 얻는다.
@MainActor
struct NaverTokenProvider: SocialAccessTokenProviding {
    func fetchAccessToken() async throws -> String {
        try await withCheckedThrowingContinuation { continuation in
            NidOAuth.shared.requestLogin { result in
                switch result {
                case let .success(login):
                    continuation.resume(returning: login.accessToken.tokenString)
                case let .failure(error):
                    if case .clientError(.canceledByUser) = error {
                        continuation.resume(throwing: SocialLoginError.cancelled)
                    } else {
                        continuation.resume(throwing: error)
                    }
                }
            }
        }
    }
}
