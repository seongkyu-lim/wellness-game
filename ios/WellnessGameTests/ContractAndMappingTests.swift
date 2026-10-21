import HealthKit
import WatchConnectivity
import XCTest

// MARK: - 서버 DTO 계약

/// 백엔드 record(`HealthActivitySyncResponse`, `CharacterSummaryResponse`, `DailyActivitiesResponse`,
/// `HealthActivitySyncRequest.ActivityPayload`)와 같은 모양의 JSON으로 iOS 모델을 확인한다.
final class BackendContractTests: XCTestCase {
    private func decode<T: Decodable>(_ type: T.Type, _ json: String) throws -> T {
        try JSONDecoder().decode(type, from: Data(json.utf8))
    }

    func test_동기화응답은_모르는_필드goals를_무시하고_캐릭터와_활동결과를_읽는다() throws {
        let response = try decode(HealthActivitySyncResponse.self, BackendFixture.syncResponse(
            level: 4, currentXp: 75, totalXp: 675, nextLevelXp: 300, gainedXp: 40, levelUp: true
        ))

        XCTAssertEqual(response.userId, "password:alice")
        XCTAssertEqual(response.date, "2026-10-21")
        XCTAssertTrue(response.levelUp)
        XCTAssertEqual(response.character, CharacterState(
            level: 4, currentXp: 75, totalXp: 675, nextLevelXp: 300,
            stats: CharacterStats(str: 3, vit: 2, intStat: 1, discipline: 4, recovery: 5)
        ))
        XCTAssertEqual(response.character.xpProgress, 0.25, accuracy: 0.0001)
        XCTAssertEqual(response.activityResults.map(\.type), ["STEPS", "SLEEP"])
        XCTAssertEqual(response.activityResults.map(\.duplicate), [false, true])
        XCTAssertEqual(Set(response.activityResults.map(\.id)).count, 2, "목록 식별자는 결과마다 달라야 한다")
    }

    func test_스탯필드명이_서버와_다르면_디코딩에_실패한다() {
        // 서버 StatsResponse는 `int`가 아니라 `intStat`이다. 이름이 어긋나면 조용히 0이 되지 않고 실패해야 한다.
        let json = BackendFixture.syncResponse().replacingOccurrences(of: "\"intStat\"", with: "\"int\"")

        XCTAssertThrowsError(try decode(HealthActivitySyncResponse.self, json))
    }

    func test_캐릭터조회_응답을_읽는다() throws {
        let json = """
        {"userId":"password:alice","character":{"level":2,"currentXp":10,"totalXp":110,"nextLevelXp":200,
          "stats":{"str":1,"vit":1,"intStat":1,"discipline":1,"recovery":1}}}
        """

        let summary = try decode(CharacterSummaryResponse.self, json)

        XCTAssertEqual(summary.userId, "password:alice")
        XCTAssertEqual(summary.character.level, 2)
        XCTAssertEqual(summary.character.xpProgress, 0.05, accuracy: 0.0001)
    }

    func test_하루활동_응답은_null과_소수_시각필드가_섞여도_필요한_값만_읽는다() throws {
        let json = """
        {"userId":"password:alice","date":"2026-10-21","activities":[
          {"type":"STEPS","source":"HEALTHKIT","durationMinutes":null,"calories":null,"distanceMeters":null,
           "steps":8500,"sleepMinutes":null,"sleepScore":null,"gainedXp":30,"startedAt":null,"endedAt":null},
          {"type":"WORKOUT","source":"WATCH","durationMinutes":45,"calories":420.5,"distanceMeters":1200.00,
           "steps":null,"sleepMinutes":null,"sleepScore":null,"gainedXp":45,
           "startedAt":"2026-10-21T07:00:00Z","endedAt":"2026-10-21T07:45:00Z"},
          {"type":"WORKOUT","source":"HEALTHKIT","durationMinutes":15,"calories":null,"distanceMeters":null,
           "steps":null,"sleepMinutes":null,"sleepScore":null,"gainedXp":15,"startedAt":null,"endedAt":null},
          {"type":"SLEEP","source":"HEALTHKIT","durationMinutes":null,"calories":null,"distanceMeters":null,
           "steps":null,"sleepMinutes":430,"sleepScore":90,"gainedXp":20,"startedAt":null,"endedAt":null}]}
        """

        let daily = try decode(DailyActivitiesResponse.self, json)
        let quests = DailyQuestProgress(activities: daily.activities)

        XCTAssertEqual(daily.activities.count, 4)
        XCTAssertEqual(daily.activities[1], ActivityEntry(type: "WORKOUT", durationMinutes: 45, steps: nil, sleepMinutes: nil, gainedXp: 45))
        XCTAssertEqual(quests, DailyQuestProgress(steps: 8_500, workoutMinutes: 60, sleepMinutes: 430))
    }

    func test_동기화요청_필드는_서버_ActivityPayload에_있는_이름만_쓴다() throws {
        // 서버 `HealthActivitySyncRequest.ActivityPayload` record 구성요소.
        let serverFields: Set<String> = [
            "type", "workoutType", "durationMinutes", "calories", "distanceMeters",
            "steps", "sleepMinutes", "sleepScore", "startedAt", "endedAt",
        ]
        let start = Date(timeIntervalSince1970: 1_792_540_800)
        let full = HealthActivityDTO(
            type: .workout, workoutType: .swimming, durationMinutes: 1, calories: 1, distanceMeters: 1,
            steps: 1, sleepMinutes: 1, sleepScore: 1, startedAt: start, endedAt: start
        )
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        let request = HealthActivitySyncRequest(userId: "u", date: "2026-10-21", activities: [full])

        let json = try XCTUnwrap(JSONSerialization.jsonObject(with: encoder.encode(request)) as? [String: Any])
        let activity = try XCTUnwrap((json["activities"] as? [[String: Any]])?.first)

        XCTAssertEqual(Set(json.keys), ["userId", "date", "activities"])
        XCTAssertEqual(Set(activity.keys), serverFields)
    }

    func test_iOS_열거값은_서버_enum_이름과_같다() {
        // 서버 com.wellnessgame.activity.ActivityType / WorkoutType.
        XCTAssertEqual(
            [HealthActivityType.steps, .workout, .sleep].map(\.rawValue),
            ["STEPS", "WORKOUT", "SLEEP"]
        )
        XCTAssertEqual(
            [WorkoutType.swimming, .running, .walking, .cycling, .strengthTraining, .other].map(\.rawValue),
            ["SWIMMING", "RUNNING", "WALKING", "CYCLING", "STRENGTH_TRAINING", "OTHER"]
        )
    }
}

// MARK: - HealthKit 수면 병합·점수

final class SleepMappingTests: XCTestCase {
    private let core = HKCategoryValueSleepAnalysis.asleepCore.rawValue
    private let rem = HKCategoryValueSleepAnalysis.asleepREM.rawValue
    private let deep = HKCategoryValueSleepAnalysis.asleepDeep.rawValue
    private let unspecified = HKCategoryValueSleepAnalysis.asleepUnspecified.rawValue
    private let inBed = HKCategoryValueSleepAnalysis.inBed.rawValue
    private let awake = HKCategoryValueSleepAnalysis.awake.rawValue

    private func todayWindow() throws -> AppleHealthKitProvider.DayWindow {
        try AppleHealthKitProvider.dayWindow(for: kst("2026-10-21 09:00"), now: kst("2026-10-21 09:00"), calendar: seoulCalendar)
    }

    private func sample(_ value: Int, _ start: String, _ end: String) -> AppleHealthKitProvider.SleepSample {
        .init(value: value, start: kst(start), end: kst(end))
    }

    func test_여러_기기의_겹치는_수면단계는_한번만_센다() throws {
        // iPhone(asleepUnspecified)과 Watch(core·deep·REM)가 같은 밤을 함께 기록한 경우.
        let samples = [
            sample(unspecified, "2026-10-20 23:00", "2026-10-21 06:00"),
            sample(core, "2026-10-20 23:10", "2026-10-21 01:00"),
            sample(deep, "2026-10-21 01:00", "2026-10-21 03:00"),
            sample(rem, "2026-10-21 05:30", "2026-10-21 06:30"),
        ]

        let sleep = try XCTUnwrap(AppleHealthKitProvider.sleepActivity(from: samples, in: todayWindow()))

        XCTAssertEqual(sleep.type, .sleep)
        XCTAssertEqual(sleep.sleepMinutes, 450)
        XCTAssertEqual(sleep.startedAt, kst("2026-10-20 23:00"))
        XCTAssertEqual(sleep.endedAt, kst("2026-10-21 06:30"))
    }

    func test_침대에_누운시간과_깬시간은_수면으로_세지_않고_중간에_깬_구간은_뺀다() throws {
        let samples = [
            sample(inBed, "2026-10-20 22:00", "2026-10-21 07:00"),
            sample(core, "2026-10-21 00:00", "2026-10-21 03:00"),
            sample(awake, "2026-10-21 03:00", "2026-10-21 03:30"),
            sample(core, "2026-10-21 03:30", "2026-10-21 06:30"),
        ]

        let sleep = try XCTUnwrap(AppleHealthKitProvider.sleepActivity(from: samples, in: todayWindow()))

        XCTAssertEqual(sleep.sleepMinutes, 360)
        XCTAssertEqual(sleep.startedAt, kst("2026-10-21 00:00"))
        XCTAssertEqual(sleep.endedAt, kst("2026-10-21 06:30"))
    }

    func test_잠든_샘플이_없으면_수면항목을_만들지_않는다() throws {
        let samples = [sample(inBed, "2026-10-20 23:00", "2026-10-21 07:00")]

        XCTAssertNil(AppleHealthKitProvider.sleepActivity(from: samples, in: try todayWindow()))
        XCTAssertNil(AppleHealthKitProvider.sleepActivity(from: [], in: try todayWindow()))
    }

    func test_아직_진행중인_수면은_오늘_몫에서_뺀다() throws {
        // 오늘 구간의 끝은 지금(09:00)이다. 지금 이후에 끝나는 샘플은 아직 깬 것이 아니다.
        let samples = [
            sample(core, "2026-10-20 23:00", "2026-10-21 06:00"),
            sample(core, "2026-10-21 08:30", "2026-10-21 10:00"),
        ]

        XCTAssertEqual(AppleHealthKitProvider.sleepActivity(from: samples, in: try todayWindow())?.sleepMinutes, 420)
    }

    func test_수면점수는_시간대별_구간을_따른다() throws {
        let cases: [(minutes: Int, score: Int)] = [
            (299, 30), (300, 50), (359, 50), (360, 70), (419, 70),
            (420, 90), (540, 90), (541, 70), (720, 70),
        ]
        let window = try todayWindow()
        for (minutes, score) in cases {
            let start = window.end.addingTimeInterval(TimeInterval(-minutes * 60))
            let samples = [AppleHealthKitProvider.SleepSample(value: core, start: start, end: window.end)]

            let sleep = AppleHealthKitProvider.sleepActivity(from: samples, in: window)

            XCTAssertEqual(sleep?.sleepMinutes, minutes)
            XCTAssertEqual(sleep?.sleepScore, score, "\(minutes)분")
        }
    }

    func test_운동항목은_활동시간을_분으로_반올림하고_거리를_그대로_싣는다() {
        let start = kst("2026-10-21 07:00")
        let dto = AppleHealthKitProvider.workoutActivity(
            workoutType: .cycling,
            activeDuration: 29 * 60 + 31,
            calories: nil,
            distanceMeters: 10_250.5,
            startedAt: start,
            endedAt: start.addingTimeInterval(40 * 60)
        )

        XCTAssertEqual(dto.type, .workout)
        XCTAssertEqual(dto.workoutType, .cycling)
        XCTAssertEqual(dto.durationMinutes, 30, "일시정지를 뺀 활동 시간 기준")
        XCTAssertNil(dto.calories)
        XCTAssertEqual(dto.distanceMeters, 10_250.5)
    }
}

// MARK: - 백그라운드 관찰 종류

final class HealthObservedTypeTests: XCTestCase {
    func test_걸음은_시간단위_운동과_수면은_즉시_전달을_요청한다() {
        XCTAssertEqual(HealthObservedType.steps.frequency, .hourly)
        XCTAssertEqual(HealthObservedType.workouts.frequency, .immediate)
        XCTAssertEqual(HealthObservedType.sleep.frequency, .immediate)
    }

    func test_관찰하는_샘플종류는_동기화가_읽는_종류와_같다() {
        XCTAssertEqual(HealthObservedType.steps.sampleType, HKQuantityType.quantityType(forIdentifier: .stepCount))
        XCTAssertEqual(HealthObservedType.workouts.sampleType, HKObjectType.workoutType())
        XCTAssertEqual(HealthObservedType.sleep.sampleType, HKObjectType.categoryType(forIdentifier: .sleepAnalysis))
    }
}

// MARK: - 서버 주소 해석 경계

final class ServerEnvironmentEdgeTests: XCTestCase {
    func test_호스트가_없는_주소는_잘못된_값이다() {
        XCTAssertEqual(ServerEnvironment.resolve("https://", allowsInsecureHTTP: false).issue, .invalid("https://"))
        XCTAssertEqual(ServerEnvironment.resolve("api.wellnessgame.example", allowsInsecureHTTP: false).issue, .invalid("api.wellnessgame.example"))
    }

    func test_스킴은_대소문자를_가리지_않는다() {
        let resolution = ServerEnvironment.resolve("HTTPS://api.wellnessgame.example", allowsInsecureHTTP: false)

        XCTAssertNil(resolution.issue)
        XCTAssertEqual(resolution.url.host, "api.wellnessgame.example")
    }

    func test_개행이_섞인_설정값도_앞뒤를_잘라_쓴다() {
        XCTAssertEqual(
            ServerEnvironment.resolve("\nhttps://api.wellnessgame.example/v1\n", allowsInsecureHTTP: false).url.absoluteString,
            "https://api.wellnessgame.example/v1"
        )
    }
}

// MARK: - Watch 로그인 정보 전달(활성화·상태 변경)

final class WatchAuthRelayDelegateTests: XCTestCase {
    private let context = WatchAuthContext(
        accessToken: "watch", userId: "password:alice", displayName: "Alice",
        expiresAt: Date(timeIntervalSince1970: 1_800_000_000), language: "en"
    )

    func test_활성화가_끝나면_보내지_못했던_마지막값을_보낸다() throws {
        let session = FakeWatchContextSession()
        session.activationState = .notActivated
        let relay = WatchAuthRelay(session: session, notificationCenter: NotificationCenter())
        relay.publish(context)
        XCTAssertTrue(session.sentContexts.isEmpty)

        session.activationState = .activated
        relay.session(WCSession.default, activationDidCompleteWith: .activated, error: nil)

        let sent = try XCTUnwrap(session.sentContexts.last)
        XCTAssertEqual(WatchAuthContext(applicationContext: sent), context)
    }

    func test_Watch앱이_설치되면_상태변경_알림에서_다시_보낸다() throws {
        let session = FakeWatchContextSession()
        session.isWatchAppInstalled = false
        let relay = WatchAuthRelay(session: session, notificationCenter: NotificationCenter())
        relay.publish(context)

        session.isWatchAppInstalled = true
        relay.sessionWatchStateDidChange(WCSession.default)

        XCTAssertEqual(session.sentContexts.count, 1)
        XCTAssertEqual(WatchAuthContext(applicationContext: try XCTUnwrap(session.sentContexts.last)), context)
    }

    func test_가짜세션이면_활성화는_아무것도_하지_않는다() {
        let session = FakeWatchContextSession()
        let relay = WatchAuthRelay(session: session, notificationCenter: NotificationCenter())

        relay.activate()
        relay.sessionDidBecomeInactive(WCSession.default)

        XCTAssertTrue(session.sentContexts.isEmpty, "보낼 값이 없으면 보내지 않는다")
    }
}
