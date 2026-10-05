import Combine
import Foundation
import GoogleSignIn
import KakaoSDKAuth
import KakaoSDKCommon
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

    /// Kakao·Naver·Apple은 서버 토큰 발급이 준비되지 않아 비활성화돼 있다.
    func signInWithKakao(_ session: UserSession) {
        session.updateStatus(String(localized: "Kakao 로그인은 아직 지원하지 않습니다."))
    }

    func signInWithNaver(_ session: UserSession) {
        session.updateStatus(String(localized: "Naver 로그인은 아직 지원하지 않습니다."))
    }

    /// 로그아웃: SDK 세션을 정리하고 Keychain 토큰을 포함한 앱 세션을 지운다.
    func signOut(_ session: UserSession) {
        if session.provider == .google {
            GIDSignIn.sharedInstance.signOut()
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
