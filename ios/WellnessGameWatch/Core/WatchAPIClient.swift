import Foundation

enum WatchAPIError: Error, Equatable {
    /// 토큰이 없거나 무효(HTTP 401). 저장된 로그인 정보를 지우고 로그인 안내로 돌아간다.
    case unauthorized
    case invalidResponse
    case server(status: Int)
}

/// Watch가 서버를 직접 조회하는 클라이언트. iPhone `NetworkClient`와 같은 서버·헤더 규칙을 쓴다.
struct WatchAPIClient {
    let baseURL: URL
    let session: URLSession

    init(baseURL: URL = ServerEnvironment.defaultBaseURL, session: URLSession = .shared) {
        self.baseURL = baseURL
        self.session = session
    }

    /// `GET /api/characters/me`. 아직 캐릭터가 없으면(404) nil.
    func fetchCharacter(credentials: WatchAuthContext) async throws -> CharacterSummaryResponse? {
        let request = makeRequest(path: "api/characters/me", credentials: credentials)
        return try await send(request, notFoundAsNil: true)
    }

    /// `GET /api/health-activities?date=yyyy-MM-dd`. 날짜는 Watch의 로컬 날짜다.
    func fetchDailyActivities(
        credentials: WatchAuthContext,
        date: Date,
        timeZone: TimeZone = .current
    ) async throws -> DailyActivitiesResponse {
        let request = makeRequest(
            path: "api/health-activities",
            query: [URLQueryItem(name: "date", value: Self.dayString(for: date, timeZone: timeZone))],
            credentials: credentials
        )
        guard let response: DailyActivitiesResponse = try await send(request, notFoundAsNil: false) else {
            throw WatchAPIError.invalidResponse
        }
        return response
    }

    /// 캐릭터와 오늘 활동을 함께 받아 스냅샷으로 만든다. 캐릭터가 아직 없으면 nil.
    func fetchSnapshot(
        credentials: WatchAuthContext,
        now: Date = Date(),
        timeZone: TimeZone = .current
    ) async throws -> CharacterSnapshot? {
        async let summary = fetchCharacter(credentials: credentials)
        async let daily = fetchDailyActivities(credentials: credentials, date: now, timeZone: timeZone)
        guard let character = (try await summary)?.character else {
            return nil
        }
        let activities = try await daily.activities
        return CharacterSnapshot(
            level: character.level,
            currentXp: character.currentXp,
            nextLevelXp: character.nextLevelXp,
            quests: DailyQuestProgress(activities: activities),
            syncedAt: now
        )
    }

    /// 서버가 받는 ISO 날짜(yyyy-MM-dd). 기기 달력·언어와 무관하게 그레고리력 숫자로 만든다.
    static func dayString(for date: Date, timeZone: TimeZone = .current) -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.calendar = Calendar(identifier: .gregorian)
        formatter.timeZone = timeZone
        formatter.dateFormat = "yyyy-MM-dd"
        return formatter.string(from: date)
    }

    func makeRequest(path: String, query: [URLQueryItem] = [], credentials: WatchAuthContext) -> URLRequest {
        var components = URLComponents(url: baseURL.appending(path: path), resolvingAgainstBaseURL: false)
        if !query.isEmpty {
            components?.queryItems = query
        }
        var request = URLRequest(url: components?.url ?? baseURL.appending(path: path))
        request.httpMethod = "GET"
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue(
            AppLanguage.normalized(credentials.language) ?? AppLanguage.acceptLanguage(),
            forHTTPHeaderField: "Accept-Language"
        )
        request.setValue("Bearer \(credentials.accessToken)", forHTTPHeaderField: "Authorization")
        return request
    }

    private func send<Response: Decodable>(_ request: URLRequest, notFoundAsNil: Bool) async throws -> Response? {
        let (data, response) = try await session.data(for: request)
        guard let httpResponse = response as? HTTPURLResponse else {
            throw WatchAPIError.invalidResponse
        }
        switch httpResponse.statusCode {
        case 200..<300:
            do {
                return try JSONDecoder().decode(Response.self, from: data)
            } catch {
                throw WatchAPIError.invalidResponse
            }
        case 401:
            throw WatchAPIError.unauthorized
        case 404 where notFoundAsNil:
            return nil
        default:
            throw WatchAPIError.server(status: httpResponse.statusCode)
        }
    }
}
