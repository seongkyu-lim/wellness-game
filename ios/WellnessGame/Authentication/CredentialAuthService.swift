import Combine
import Foundation

/// 아이디/비밀번호 회원가입·로그인을 백엔드 API로 처리하고, 발급된 토큰으로 세션을 갱신한다.
@MainActor
final class CredentialAuthService: ObservableObject {
    @Published private(set) var isLoading = false
    @Published var errorMessage: String?

    private let networkClient: NetworkClient

    init(networkClient: NetworkClient = NetworkClient()) {
        self.networkClient = networkClient
    }

    func signUp(username: String, password: String, into session: UserSession) async {
        await authenticate(session: session, statusMessage: String(localized: "회원가입을 진행하고 있습니다.")) {
            try await self.networkClient.signUp(
                SignUpRequest(username: username, password: password, displayName: nil)
            )
        }
    }

    func logIn(username: String, password: String, into session: UserSession) async {
        await authenticate(session: session, statusMessage: String(localized: "로그인을 진행하고 있습니다.")) {
            try await self.networkClient.logIn(
                LoginRequest(username: username, password: password)
            )
        }
    }

    private func authenticate(
        session: UserSession,
        statusMessage: String,
        _ request: @escaping () async throws -> AuthTokenResponse
    ) async {
        isLoading = true
        errorMessage = nil
        session.updateStatus(statusMessage)
        defer { isLoading = false }

        do {
            let response = try await request()
            try session.signIn(provider: .password, auth: response)
        } catch {
            let message = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
            errorMessage = message
            session.updateStatus(message)
        }
    }
}
