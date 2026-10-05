import Foundation

enum NetworkError: LocalizedError, Equatable {
    case invalidResponse
    /// 토큰이 없거나 무효(HTTP 401). 세션을 정리하고 재로그인을 안내해야 한다.
    case unauthorized(message: String)
    case server(status: Int, message: String)

    var errorDescription: String? {
        switch self {
        case .invalidResponse:
            return String(localized: "서버 응답을 확인하지 못했습니다.")
        case let .unauthorized(message):
            return message.isEmpty ? String(localized: "인증이 필요합니다. 다시 로그인해 주세요.") : message
        case let .server(status, message):
            return String(localized: "서버 오류(\(status)): \(message)")
        }
    }
}

struct NetworkClient {
    let baseURL: URL
    let session: URLSession
    /// 값이 있으면 보호 API 요청에 `Authorization: Bearer <token>`을 붙인다.
    let tokenStore: AccessTokenStore?

    init(
        baseURL: URL = ServerEnvironment.defaultBaseURL,
        session: URLSession = .shared,
        tokenStore: AccessTokenStore? = nil
    ) {
        self.baseURL = baseURL
        self.session = session
        self.tokenStore = tokenStore
    }

    func sync(_ requestBody: HealthActivitySyncRequest) async throws -> HealthActivitySyncResponse {
        try await post(path: "api/health-activities/sync", body: requestBody, authorized: true)
    }

    func signUp(_ requestBody: SignUpRequest) async throws -> AuthTokenResponse {
        try await post(path: "api/auth/signup", body: requestBody, authorized: false)
    }

    func logIn(_ requestBody: LoginRequest) async throws -> AuthTokenResponse {
        try await post(path: "api/auth/login", body: requestBody, authorized: false)
    }

    /// Google SDK가 발급한 ID 토큰을 서버 토큰으로 교환한다.
    func signInWithGoogle(idToken: String) async throws -> AuthTokenResponse {
        try await post(
            path: "api/auth/google/native",
            body: GoogleNativeLoginRequest(idToken: idToken),
            authorized: false
        )
    }

    /// Kakao SDK가 발급한 액세스 토큰을 서버 토큰으로 교환한다.
    func signInWithKakao(accessToken: String) async throws -> AuthTokenResponse {
        try await post(
            path: "api/auth/kakao/native",
            body: SocialAccessTokenRequest(accessToken: accessToken),
            authorized: false
        )
    }

    /// Naver SDK가 발급한 액세스 토큰을 서버 토큰으로 교환한다.
    /// 서버가 토큰 대체 공격을 막을 수 있도록 refresh token도 함께 보낸다.
    func signInWithNaver(accessToken: String, refreshToken: String) async throws -> AuthTokenResponse {
        try await post(
            path: "api/auth/naver/native",
            body: NaverNativeLoginRequest(accessToken: accessToken, refreshToken: refreshToken),
            authorized: false
        )
    }

    /// Sign in with Apple의 identity token(과 최초 로그인 시에만 오는 이름)을 서버 토큰으로 교환한다.
    func signInWithApple(identityToken: String, fullName: String?) async throws -> AuthTokenResponse {
        try await post(
            path: "api/auth/apple/native",
            body: AppleNativeLoginRequest(identityToken: identityToken, fullName: fullName),
            authorized: false
        )
    }

    private func post<Body: Encodable, Response: Decodable>(
        path: String,
        body: Body,
        authorized: Bool
    ) async throws -> Response {
        let endpoint = baseURL.appending(path: path)
        var request = URLRequest(url: endpoint)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        // 서버가 메시지를 번역할 수 있도록 지원 언어(ko·en)를 보낸다.
        request.setValue(AppLanguage.acceptLanguage(), forHTTPHeaderField: "Accept-Language")
        if authorized, let token = tokenStore?.loadToken() {
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }

        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        request.httpBody = try encoder.encode(body)

        let (data, response) = try await session.data(for: request)
        guard let httpResponse = response as? HTTPURLResponse else {
            throw NetworkError.invalidResponse
        }
        guard 200..<300 ~= httpResponse.statusCode else {
            let serverMessage = try? JSONDecoder().decode(ServerError.self, from: data).message
            if httpResponse.statusCode == 401 {
                throw NetworkError.unauthorized(message: serverMessage ?? "")
            }
            let message = serverMessage
                ?? String(data: data, encoding: .utf8)
                ?? String(localized: "알 수 없는 오류")
            throw NetworkError.server(status: httpResponse.statusCode, message: message)
        }
        return try JSONDecoder().decode(Response.self, from: data)
    }
}

struct SignUpRequest: Encodable {
    let username: String
    let password: String
    let displayName: String?
}

struct LoginRequest: Encodable {
    let username: String
    let password: String
}

struct GoogleNativeLoginRequest: Encodable {
    let idToken: String
}

/// Kakao·Naver 네이티브 로그인 요청. SDK가 발급한 액세스 토큰만 보낸다.
struct SocialAccessTokenRequest: Encodable {
    let accessToken: String
}

/// Naver 네이티브 로그인 요청.
struct NaverNativeLoginRequest: Encodable {
    let accessToken: String
    let refreshToken: String
}

/// Apple 네이티브 로그인 요청. `fullName`은 Apple이 최초 인증에만 주므로 없으면 생략한다.
struct AppleNativeLoginRequest: Encodable {
    let identityToken: String
    let fullName: String?
}

/// 로그인·회원가입·구글 토큰 교환의 공통 응답.
/// ID/비밀번호 응답에는 기존 필드 `userIdentifier`가 함께 온다.
struct AuthTokenResponse: Decodable, Equatable {
    let userId: String
    let displayName: String?
    let accessToken: String
    let tokenType: String?
    let expiresIn: Int?
    let userIdentifier: String?
}

private struct ServerError: Decodable {
    let message: String
}
