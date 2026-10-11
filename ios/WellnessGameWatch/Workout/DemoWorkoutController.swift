#if DEBUG
import Foundation

/// Debug 전용 가짜 세션. SwiftUI 미리보기와 시뮬레이터 화면 확인(`-WorkoutDemo` 실행 인자)에서 쓴다.
/// HealthKit 권한을 묻거나 저장하지 않고 1초마다 그럴듯한 수치를 만든다.
@MainActor
final class DemoWorkoutController: WorkoutSessionControlling {
    static var isEnabled: Bool {
        ProcessInfo.processInfo.arguments.contains("-WorkoutDemo")
    }

    var onEvent: ((WorkoutEvent) -> Void)?
    var onMetrics: ((WorkoutMetrics) -> Void)?
    let isHealthDataAvailable = true

    private var kind: WorkoutKind = .other
    private var startDate = Date()
    private var accumulated: TimeInterval = 0
    private var runningSince: Date?
    private var timer: Timer?
    private var heartRates: [Double] = []

    func requestAuthorization() async throws {}

    func start(_ kind: WorkoutKind) async throws {
        self.kind = kind
        startDate = Date()
        accumulated = 0
        heartRates = []
        runningSince = startDate
        startTimer()
        onEvent?(.sessionStarted)
    }

    func pause() {
        guard let runningSince else { return }
        accumulated += Date().timeIntervalSince(runningSince)
        self.runningSince = nil
        onEvent?(.sessionPaused)
    }

    func resume() {
        runningSince = Date()
        onEvent?(.sessionResumed)
    }

    func end() {
        timer?.invalidate()
        let endDate = Date()
        let summary = WorkoutSummary(
            kind: kind,
            startDate: startDate,
            endDate: endDate,
            activeDuration: elapsedTime(at: endDate),
            metrics: metrics(at: endDate)
        )
        runningSince = nil
        onEvent?(.sessionFinished(summary))
    }

    func elapsedTime(at date: Date) -> TimeInterval {
        accumulated + (runningSince.map { date.timeIntervalSince($0) } ?? 0)
    }

    private func startTimer() {
        timer?.invalidate()
        timer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in
            Task { @MainActor in
                guard let self, self.runningSince != nil else { return }
                self.heartRates.append(Double(118 + self.heartRates.count % 12))
                self.onMetrics?(self.metrics(at: Date()))
            }
        }
    }

    private func metrics(at date: Date) -> WorkoutMetrics {
        let minutes = elapsedTime(at: date) / 60
        return WorkoutMetrics(
            heartRate: heartRates.last,
            averageHeartRate: heartRates.isEmpty ? nil : heartRates.reduce(0, +) / Double(heartRates.count),
            activeCalories: minutes * 9,
            distanceMeters: kind.tracksDistance ? minutes * 160 : nil
        )
    }
}
#endif
