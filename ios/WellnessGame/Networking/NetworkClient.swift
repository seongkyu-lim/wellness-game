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

struct NetworkClient: Sendable {
    /// 액세스 토큰이 이 시간 안에 만료되면 요청 전에 미리 갱신한다.
    static let refreshLeeway: TimeInterval = 60
    /// 로그아웃 요청은 실패해도 로컬 로그아웃을 진행하므로 오래 기다리지 않는다.
    static let logoutTimeout: TimeInterval = 10

    let baseURL: URL
    let session: URLSession
    /// 값이 있으면 보호 API 요청에 `Authorization: Bearer <token>`을 붙이고, 401·만료 임박 시 리프레시한다.
    let credentials: SessionCredentialStore?
    private let now: @Sendable () -> Date

    init(
        baseURL: URL = ServerEnvironment.defaultBaseURL,
        session: URLSession = .shared,
        credentials: SessionCredentialStore? = nil,
        now: @escaping @Sendable () -> Date = { Date() }
    ) {
        self.baseURL = baseURL
        self.session = session
        self.credentials = credentials
        self.now = now
    }

    func sync(_ requestBody: HealthActivitySyncRequest) async throws -> HealthActivitySyncResponse {
        try await authorizedPost(path: "api/health-activities/sync", body: requestBody)
    }

    func signUp(_ requestBody: SignUpRequest) async throws -> AuthTokenResponse {
        try await post(path: "api/auth/signup", body: requestBody)
    }

    func logIn(_ requestBody: LoginRequest) async throws -> AuthTokenResponse {
        try await post(path: "api/auth/login", body: requestBody)
    }

    /// Google SDK가 발급한 ID 토큰을 서버 토큰으로 교환한다.
    func signInWithGoogle(idToken: String) async throws -> AuthTokenResponse {
        try await post(path: "api/auth/google/native", body: GoogleNativeLoginRequest(idToken: idToken))
    }

    /// Kakao SDK가 발급한 액세스 토큰을 서버 토큰으로 교환한다.
    func signInWithKakao(accessToken: String) async throws -> AuthTokenResponse {
        try await post(path: "api/auth/kakao/native", body: SocialAccessTokenRequest(accessToken: accessToken))
    }

    /// Naver SDK가 발급한 액세스 토큰을 서버 토큰으로 교환한다.
    /// 서버가 토큰 대체 공격을 막을 수 있도록 refresh token도 함께 보낸다.
    func signInWithNaver(accessToken: String, refreshToken: String) async throws -> AuthTokenResponse {
        try await post(
            path: "api/auth/naver/native",
            body: NaverNativeLoginRequest(accessToken: accessToken, refreshToken: refreshToken)
        )
    }

    /// Sign in with Apple의 identity token(과 최초 로그인 시에만 오는 이름)을 서버 토큰으로 교환한다.
    /// `nonce`는 Apple 요청에 SHA-256 해시로 넣은 값의 원문이다.
    func signInWithApple(identityToken: String, fullName: String?, nonce: String?) async throws -> AuthTokenResponse {
        try await post(
            path: "api/auth/apple/native",
            body: AppleNativeLoginRequest(identityToken: identityToken, fullName: fullName, nonce: nonce)
        )
    }

    /// 이 기기 로그아웃. 저장된 리프레시 토큰의 family를 서버에서 폐기한다.
    func logOut() async throws {
        let body = LogoutRequest(refreshToken: credentials?.refreshToken)
        let _: EmptyResponse = try await authorizedPost(path: "api/auth/logout", body: body, timeout: Self.logoutTimeout)
    }

    /// Watch 전용 조회(`scope: read`) 토큰을 발급받는다. 전체 권한 토큰으로만 발급된다.
    func issueWatchToken() async throws -> WatchTokenResponse {
        try await authorizedPost(path: "api/auth/watch-token", body: EmptyBody())
    }

    // MARK: - 인증 요청

    /// 보호 API 요청. 만료 임박이면 미리 갱신하고, 401이면 리프레시 후 한 번만 다시 보낸다.
    /// 리프레시가 거절되면(또는 리프레시 토큰이 없으면) `NetworkError.unauthorized`를 던진다.
    private func authorizedPost<Body: Encodable, Response: Decodable>(
        path: String,
        body: Body,
        timeout: TimeInterval? = nil
    ) async throws -> Response {
        guard let credentials else {
            return try await post(path: path, body: body, bearer: nil, timeout: timeout)
        }
        let token = try await accessTokenForRequest(credentials)
        do {
            return try await post(path: path, body: body, bearer: token, timeout: timeout)
        } catch NetworkError.unauthorized where credentials.refreshToken != nil {
            let renewed = try await refreshAccessToken(rejecting: token, credentials)
            return try await post(path: path, body: body, bearer: renewed, timeout: timeout)
        }
    }

    /// 요청에 실을 액세스 토큰. 곧 만료되면 미리 갱신한다. 갱신이 네트워크 오류로 실패하면 기존 토큰으로 시도한다.
    private func accessTokenForRequest(_ credentials: SessionCredentialStore) async throws -> String? {
        let token = credentials.accessToken
        let date = now()
        guard credentials.hasUsableRefreshToken(at: date),
              credentials.accessTokenNeedsRefresh(at: date, leeway: Self.refreshLeeway) else {
            return token
        }
        do {
            return try await refreshAccessToken(rejecting: token, credentials)
        } catch let error as NetworkError {
            if case .unauthorized = error { throw error }
            return token
        } catch {
            return token
        }
    }

    /// `POST /api/auth/refresh`를 단일 비행으로 보내고, 회전된 토큰을 저장한 뒤 새 액세스 토큰을 돌려준다.
    private func refreshAccessToken(rejecting token: String?, _ credentials: SessionCredentialStore) async throws -> String {
        let client = self
        return try await credentials.refreshCoordinator.refresh(
            rejecting: token,
            currentToken: { credentials.accessToken }
        ) {
            guard let refreshToken = credentials.refreshToken,
                  credentials.hasUsableRefreshToken(at: client.now()) else {
                throw NetworkError.unauthorized(message: "")
            }
            let response: RefreshResponse = try await client.post(
                path: "api/auth/refresh",
                body: RefreshRequest(refreshToken: refreshToken)
            )
            try credentials.save(response.sessionTokens, now: client.now())
            return response.accessToken
        }
    }

    // MARK: - 전송

    private func post<Body: Encodable, Response: Decodable>(
        path: String,
        body: Body,
        bearer: String? = nil,
        timeout: TimeInterval? = nil
    ) async throws -> Response {
        let endpoint = baseURL.appending(path: path)
        var request = URLRequest(url: endpoint)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        // 서버가 메시지를 번역할 수 있도록 지원 언어(ko·en)를 보낸다.
        request.setValue(AppLanguage.acceptLanguage(), forHTTPHeaderField: "Accept-Language")
        if let bearer {
            request.setValue("Bearer \(bearer)", forHTTPHeaderField: "Authorization")
        }
        if let timeout {
            request.timeoutInterval = timeout
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
        if Response.self == EmptyResponse.self, let empty = EmptyResponse() as? Response {
            return empty
        }
        return try JSONDecoder().decode(Response.self, from: data)
    }
}

/// 본문이 없는 요청(`{}`).
private struct EmptyBody: Encodable {}

/// 본문이 없는 응답(204).
private struct EmptyResponse: Decodable {}

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
/// `nonce`는 Apple 요청에 해시로 넣은 원문이다(없으면 생략).
struct AppleNativeLoginRequest: Encodable {
    let identityToken: String
    let fullName: String?
    let nonce: String?
}

struct RefreshRequest: Encodable {
    let refreshToken: String
}

/// 로그아웃 요청. 리프레시 토큰이 없으면(이전 세션) 생략한다.
struct LogoutRequest: Encodable {
    let refreshToken: String?
}

/// 로그인·회원가입·구글 토큰 교환의 공통 응답.
/// ID/비밀번호 응답에는 기존 필드 `userIdentifier`가 함께 온다.
/// Auth v2부터 `refreshToken`·`refreshExpiresIn`(초)이 함께 온다. 이전 서버 응답에는 없을 수 있다.
struct AuthTokenResponse: Decodable, Equatable {
    let userId: String
    let displayName: String?
    let accessToken: String
    let tokenType: String?
    let expiresIn: Int?
    let userIdentifier: String?
    let refreshToken: String?
    let refreshExpiresIn: Int?

    init(
        userId: String,
        displayName: String?,
        accessToken: String,
        tokenType: String?,
        expiresIn: Int?,
        userIdentifier: String?,
        refreshToken: String? = nil,
        refreshExpiresIn: Int? = nil
    ) {
        self.userId = userId
        self.displayName = displayName
        self.accessToken = accessToken
        self.tokenType = tokenType
        self.expiresIn = expiresIn
        self.userIdentifier = userIdentifier
        self.refreshToken = refreshToken
        self.refreshExpiresIn = refreshExpiresIn
    }

    var sessionTokens: SessionTokens {
        SessionTokens(
            accessToken: accessToken,
            expiresIn: expiresIn,
            refreshToken: refreshToken,
            refreshExpiresIn: refreshExpiresIn
        )
    }
}

/// `POST /api/auth/refresh` 응답. 리프레시 토큰은 매번 회전된다.
struct RefreshResponse: Decodable, Equatable {
    let userId: String
    let accessToken: String
    let tokenType: String?
    let expiresIn: Int?
    let refreshToken: String
    let refreshExpiresIn: Int?

    var sessionTokens: SessionTokens {
        SessionTokens(
            accessToken: accessToken,
            expiresIn: expiresIn,
            refreshToken: refreshToken,
            refreshExpiresIn: refreshExpiresIn
        )
    }
}

/// `POST /api/auth/watch-token` 응답. `scope: read` 토큰이며 리프레시 토큰은 없다.
struct WatchTokenResponse: Decodable, Equatable {
    let accessToken: String
    let tokenType: String?
    let expiresIn: Int
}

private struct ServerError: Decodable {
    let message: String
}
