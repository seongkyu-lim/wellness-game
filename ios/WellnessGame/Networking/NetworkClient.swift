import Foundation

enum NetworkError: LocalizedError {
    case invalidResponse
    case server(status: Int, message: String)

    var errorDescription: String? {
        switch self {
        case .invalidResponse:
            return "서버 응답을 확인하지 못했습니다."
        case let .server(status, message):
            return "서버 오류(\(status)): \(message)"
        }
    }
}

struct NetworkClient {
    let baseURL: URL
    let session: URLSession

    init(
        baseURL: URL = URL(string: "http://127.0.0.1:8080")!,
        session: URLSession = .shared
    ) {
        self.baseURL = baseURL
        self.session = session
    }

    func sync(_ requestBody: HealthActivitySyncRequest) async throws -> HealthActivitySyncResponse {
        try await post(path: "api/health-activities/sync", body: requestBody)
    }

    func signUp(_ requestBody: SignUpRequest) async throws -> PasswordAuthResponse {
        try await post(path: "api/auth/signup", body: requestBody)
    }

    func logIn(_ requestBody: LoginRequest) async throws -> PasswordAuthResponse {
        try await post(path: "api/auth/login", body: requestBody)
    }

    private func post<Body: Encodable, Response: Decodable>(
        path: String,
        body: Body
    ) async throws -> Response {
        let endpoint = baseURL.appending(path: path)
        var request = URLRequest(url: endpoint)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")

        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        request.httpBody = try encoder.encode(body)

        let (data, response) = try await session.data(for: request)
        guard let httpResponse = response as? HTTPURLResponse else {
            throw NetworkError.invalidResponse
        }
        guard 200..<300 ~= httpResponse.statusCode else {
            let message = (try? JSONDecoder().decode(ServerError.self, from: data).message)
                ?? String(data: data, encoding: .utf8)
                ?? "알 수 없는 오류"
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

struct PasswordAuthResponse: Decodable {
    let userIdentifier: String
    let displayName: String?
}

private struct ServerError: Decodable {
    let message: String
}

