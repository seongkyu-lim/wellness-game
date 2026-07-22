import Combine
import Foundation

/// 아이디/비밀번호 회원가입·로그인을 백엔드 API로 처리하고 세션에 반영한다.
@MainActor
final class CredentialAuthService: ObservableObject {
    @Published private(set) var isLoading = false
    @Published var errorMessage: String?

    private let networkClient: NetworkClient

    init(networkClient: NetworkClient = NetworkClient()) {
        self.networkClient = networkClient
    }

    func signUp(username: String, password: String, into session: UserSession) async {
        await authenticate(session: session, statusMessage: "회원가입을 진행하고 있습니다.") {
            try await self.networkClient.signUp(
                SignUpRequest(username: username, password: password, displayName: nil)
            )
        }
    }

    func logIn(username: String, password: String, into session: UserSession) async {
        await authenticate(session: session, statusMessage: "로그인을 진행하고 있습니다.") {
            try await self.networkClient.logIn(
                LoginRequest(username: username, password: password)
            )
        }
    }

    private func authenticate(
        session: UserSession,
        statusMessage: String,
        _ request: @escaping () async throws -> PasswordAuthResponse
    ) async {
        isLoading = true
        errorMessage = nil
        session.updateStatus(statusMessage)
        defer { isLoading = false }

        do {
            let response = try await request()
            session.signIn(
                provider: .password,
                userIdentifier: response.userIdentifier,
                displayName: response.displayName
            )
        } catch {
            let message: String
            if let networkError = error as? NetworkError {
                message = networkError.errorDescription ?? "인증에 실패했습니다."
            } else {
                message = error.localizedDescription
            }
            errorMessage = message
            session.updateStatus(message)
        }
    }
}
