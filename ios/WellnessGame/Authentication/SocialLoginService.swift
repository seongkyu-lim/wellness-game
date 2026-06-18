import Combine
import Foundation
import GoogleSignIn
import KakaoSDKAuth
import KakaoSDKCommon
import KakaoSDKUser
import UIKit

@MainActor
final class SocialLoginService: ObservableObject {
    @Published private(set) var isLoading = false

    private var didAttemptRestore = false

    static func initializeKakaoIfConfigured() {
        guard let appKey = configurationValue(for: "KAKAO_NATIVE_APP_KEY") else {
            return
        }
        KakaoSDK.initSDK(appKey: appKey)
    }

    static func handleOpenURL(_ url: URL) -> Bool {
        if GIDSignIn.sharedInstance.handle(url) {
            return true
        }
        if configurationValue(for: "KAKAO_NATIVE_APP_KEY") != nil,
           AuthApi.isKakaoTalkLoginUrl(url) {
            return AuthController.handleOpenUrl(url: url)
        }
        return false
    }

    func restoreSessionIfNeeded(_ session: UserSession) {
        guard !didAttemptRestore else {
            return
        }
        didAttemptRestore = true

        switch session.provider {
        case .google:
            restoreGoogleSession(session)
        case .kakao:
            restoreKakaoSession(session)
        case .apple, .none:
            break
        }
    }

    func signInWithGoogle(_ session: UserSession) {
        guard Self.configurationValue(for: "GIDClientID") != nil else {
            session.updateStatus("Google OAuth 클라이언트 ID를 먼저 설정해 주세요.")
            return
        }
        guard let presenter = Self.presentingViewController() else {
            session.updateStatus("Google 로그인 화면을 표시하지 못했습니다.")
            return
        }

        isLoading = true
        session.updateStatus("Google 로그인을 진행하고 있습니다.")
        GIDSignIn.sharedInstance.signIn(withPresenting: presenter) { [weak self] result, error in
            Task { @MainActor in
                self?.isLoading = false
                if let error {
                    session.updateStatus("Google 로그인에 실패했습니다: \(error.localizedDescription)")
                    return
                }
                guard let user = result?.user, let identifier = user.userID else {
                    session.updateStatus("Google 사용자 정보를 확인하지 못했습니다.")
                    return
                }
                let name = user.profile?.name ?? user.profile?.email
                session.signIn(provider: .google, userIdentifier: identifier, displayName: name)
            }
        }
    }

    func signInWithKakao(_ session: UserSession) {
        guard Self.configurationValue(for: "KAKAO_NATIVE_APP_KEY") != nil else {
            session.updateStatus("Kakao 네이티브 앱 키를 먼저 설정해 주세요.")
            return
        }

        isLoading = true
        session.updateStatus("Kakao 로그인을 진행하고 있습니다.")

        let completion: (OAuthToken?, Error?) -> Void = { [weak self] _, error in
            Task { @MainActor in
                if let error {
                    self?.isLoading = false
                    session.updateStatus("Kakao 로그인에 실패했습니다: \(error.localizedDescription)")
                    return
                }
                self?.loadKakaoProfile(session)
            }
        }

        if UserApi.isKakaoTalkLoginAvailable() {
            UserApi.shared.loginWithKakaoTalk(launchMethod: .CustomScheme, completion: completion)
        } else {
            UserApi.shared.loginWithKakaoAccount(completion: completion)
        }
    }

    func signOut(_ session: UserSession) {
        switch session.provider {
        case .google:
            GIDSignIn.sharedInstance.signOut()
            session.signOut()
        case .kakao:
            UserApi.shared.logout { error in
                Task { @MainActor in
                    if let error {
                        session.signOut(
                            message: "Kakao 로그아웃 요청은 실패했지만 기기 세션을 지우고 게스트로 전환했습니다. (\(error.localizedDescription))"
                        )
                    } else {
                        session.signOut()
                    }
                }
            }
        case .apple, .none:
            session.signOut()
        }
    }

    private func restoreGoogleSession(_ session: UserSession) {
        guard Self.configurationValue(for: "GIDClientID") != nil else {
            session.signOut(message: "Google 설정이 없어 게스트로 전환했습니다.")
            return
        }

        isLoading = true
        GIDSignIn.sharedInstance.restorePreviousSignIn { [weak self] user, error in
            Task { @MainActor in
                self?.isLoading = false
                guard error == nil, let user, let identifier = user.userID else {
                    session.signOut(message: "Google 로그인 상태가 만료되어 게스트로 전환했습니다.")
                    return
                }
                let name = user.profile?.name ?? user.profile?.email
                session.signIn(provider: .google, userIdentifier: identifier, displayName: name)
            }
        }
    }

    private func restoreKakaoSession(_ session: UserSession) {
        guard Self.configurationValue(for: "KAKAO_NATIVE_APP_KEY") != nil, AuthApi.hasToken() else {
            session.signOut(message: "Kakao 로그인 상태가 없어 게스트로 전환했습니다.")
            return
        }

        isLoading = true
        UserApi.shared.accessTokenInfo { [weak self] _, error in
            Task { @MainActor in
                if error != nil {
                    self?.isLoading = false
                    session.signOut(message: "Kakao 로그인 상태가 만료되어 게스트로 전환했습니다.")
                    return
                }
                self?.loadKakaoProfile(session)
            }
        }
    }

    private func loadKakaoProfile(_ session: UserSession) {
        UserApi.shared.me { [weak self] user, error in
            Task { @MainActor in
                self?.isLoading = false
                if let error {
                    session.updateStatus("Kakao 사용자 정보 조회에 실패했습니다: \(error.localizedDescription)")
                    return
                }
                guard let user, let identifier = user.id else {
                    session.updateStatus("Kakao 사용자 정보를 확인하지 못했습니다.")
                    return
                }
                let name = user.kakaoAccount?.profile?.nickname ?? user.kakaoAccount?.email
                session.signIn(provider: .kakao, userIdentifier: String(identifier), displayName: name)
            }
        }
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
