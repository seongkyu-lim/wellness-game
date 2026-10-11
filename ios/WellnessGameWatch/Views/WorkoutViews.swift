import SwiftUI

// MARK: - 종목 표시

extension WorkoutKind {
    var title: LocalizedStringKey {
        switch self {
        case .running: "달리기"
        case .walking: "걷기"
        case .cycling: "자전거"
        case .swimming: "수영"
        case .strengthTraining: "근력 운동"
        case .other: "기타"
        }
    }

    var symbolName: String {
        switch self {
        case .running: "figure.run"
        case .walking: "figure.walk"
        case .cycling: "figure.outdoor.cycle"
        case .swimming: "figure.pool.swim"
        case .strengthTraining: "figure.strengthtraining.traditional"
        case .other: "figure.mixed.cardio"
        }
    }

    var tint: Color {
        switch self {
        case .running: WatchTheme.peach
        case .walking: WatchTheme.mint
        case .cycling: WatchTheme.yellow
        case .swimming: WatchTheme.lilac
        case .strengthTraining: WatchTheme.primary
        case .other: WatchTheme.xp
        }
    }
}

// MARK: - 홈의 진입 버튼

struct StartWorkoutButton: View {
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Label("운동 시작", systemImage: "figure.run")
                .font(.watchRounded(.body))
                .frame(maxWidth: .infinity)
        }
        .tint(WatchTheme.peach)
    }
}

// MARK: - 종목 선택

struct WorkoutKindPicker: View {
    let onSelect: (WorkoutKind) -> Void

    var body: some View {
        List(WorkoutKind.allCases) { kind in
            Button {
                onSelect(kind)
            } label: {
                HStack(spacing: 10) {
                    Image(systemName: kind.symbolName)
                        .font(.body)
                        .foregroundStyle(WatchTheme.onPop)
                        .frame(width: 30, height: 30)
                        .background(Circle().fill(kind.tint))
                        .accessibilityHidden(true)
                    VStack(alignment: .leading, spacing: 1) {
                        Text(kind.title)
                            .font(.watchRounded(.body))
                            .foregroundStyle(WatchTheme.textPrimary)
                        if kind == .swimming {
                            Text("수영장 \(Int(WorkoutKind.defaultPoolLapLengthMeters))m")
                                .font(.watchRounded(.caption2, weight: .regular))
                                .foregroundStyle(WatchTheme.textSecondary)
                        }
                    }
                }
            }
        }
        .navigationTitle("운동 종류")
    }
}

// MARK: - 세션 화면(운동 중·요약·오류)

struct WorkoutSessionView: View {
    @ObservedObject var model: WorkoutViewModel

    var body: some View {
        Group {
            switch model.phase {
            case .ended(let summary):
                WorkoutSummaryView(summary: summary, onClose: model.dismiss)
            case .failed(let failure):
                WorkoutFailureView(failure: failure, onClose: model.dismiss)
            case .idle:
                Color.clear
            case .starting, .running, .paused, .ending:
                WorkoutLiveView(model: model)
            }
        }
        .background(WatchTheme.background.ignoresSafeArea())
    }
}

private struct WorkoutLiveView: View {
    @ObservedObject var model: WorkoutViewModel

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 6) {
                header
                TimelineView(.periodic(from: .now, by: 1)) { context in
                    Text(WorkoutFormat.duration(model.elapsedTime(at: context.date)))
                        .font(.system(.title2, design: .rounded).weight(.black))
                        .foregroundStyle(model.isPaused ? WatchTheme.textSecondary : WatchTheme.yellow)
                        .monospacedDigit()
                        .accessibilityLabel(Text("시간"))
                        .accessibilityValue(Text(WorkoutFormat.duration(model.elapsedTime(at: context.date))))
                }
                MetricRow(icon: "heart.fill", tint: WatchTheme.peach, title: "심박수", value: heartRateText)
                MetricRow(icon: "flame.fill", tint: WatchTheme.yellow, title: "활동 칼로리", value: caloriesText)
                if model.phase.kind?.tracksDistance == true {
                    MetricRow(icon: "location.fill", tint: WatchTheme.mint, title: "거리", value: distanceText)
                }
                controls
                    .padding(.top, 4)
            }
            .padding(.horizontal, 2)
        }
    }

    @ViewBuilder
    private var header: some View {
        if let kind = model.phase.kind {
            HStack(spacing: 6) {
                Image(systemName: kind.symbolName)
                    .foregroundStyle(kind.tint)
                    .accessibilityHidden(true)
                Text(kind.title)
                    .font(.watchDisplay(.footnote))
                    .foregroundStyle(WatchTheme.textPrimary)
                Spacer(minLength: 0)
                statusBadge
            }
        }
    }

    @ViewBuilder
    private var statusBadge: some View {
        switch model.phase {
        case .starting:
            Text("준비 중…")
                .font(.watchRounded(.caption2, weight: .regular))
                .foregroundStyle(WatchTheme.textSecondary)
        case .paused:
            Text("일시정지됨")
                .font(.watchRounded(.caption2))
                .foregroundStyle(WatchTheme.yellow)
        case .ending:
            Text("저장 중…")
                .font(.watchRounded(.caption2, weight: .regular))
                .foregroundStyle(WatchTheme.textSecondary)
        default:
            EmptyView()
        }
    }

    @ViewBuilder
    private var controls: some View {
        if case .ending = model.phase {
            ProgressView()
                .frame(maxWidth: .infinity)
        } else {
            let isStarting: Bool = {
                if case .starting = model.phase { return true }
                return false
            }()
            HStack(spacing: 6) {
                Button(action: model.togglePause) {
                    Label(model.isPaused ? "재개" : "일시정지", systemImage: model.isPaused ? "play.fill" : "pause.fill")
                        .labelStyle(.iconOnly)
                        .frame(maxWidth: .infinity)
                }
                .tint(WatchTheme.yellow)
                Button(role: .destructive, action: model.end) {
                    Label("종료", systemImage: "xmark")
                        .labelStyle(.iconOnly)
                        .frame(maxWidth: .infinity)
                }
                .tint(WatchTheme.peach)
            }
            .disabled(isStarting)
        }
    }

    private var heartRateText: Text {
        model.metrics.heartRate.map { Text("\(Int($0.rounded())) bpm") } ?? Text(verbatim: "--")
    }

    private var caloriesText: Text {
        model.metrics.activeCalories.map { Text("\(Int($0.rounded())) kcal") } ?? Text(verbatim: "--")
    }

    private var distanceText: Text {
        model.metrics.distanceMeters.map { Text(WorkoutFormat.distance($0)) } ?? Text(verbatim: "--")
    }
}

private struct MetricRow: View {
    let icon: String
    let tint: Color
    let title: LocalizedStringKey
    let value: Text

    var body: some View {
        HStack(spacing: 6) {
            Image(systemName: icon)
                .font(.caption2)
                .foregroundStyle(tint)
                .frame(width: 16)
                .accessibilityHidden(true)
            Text(title)
                .font(.watchRounded(.caption2, weight: .regular))
                .foregroundStyle(WatchTheme.textSecondary)
            Spacer(minLength: 4)
            value
                .font(.watchRounded(.footnote))
                .foregroundStyle(WatchTheme.textPrimary)
                .monospacedDigit()
        }
        .accessibilityElement(children: .combine)
    }
}

private struct WorkoutSummaryView: View {
    let summary: WorkoutSummary
    let onClose: () -> Void

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 6) {
                Label("운동 완료", systemImage: "checkmark.circle.fill")
                    .font(.watchDisplay(.headline))
                    .foregroundStyle(WatchTheme.mint)
                HStack(spacing: 6) {
                    Image(systemName: summary.kind.symbolName)
                        .foregroundStyle(summary.kind.tint)
                        .accessibilityHidden(true)
                    Text(summary.kind.title)
                        .font(.watchRounded(.footnote))
                        .foregroundStyle(WatchTheme.textPrimary)
                }
                MetricRow(icon: "timer", tint: WatchTheme.yellow, title: "시간",
                          value: Text(WorkoutFormat.duration(summary.activeDuration)))
                MetricRow(icon: "flame.fill", tint: WatchTheme.yellow, title: "활동 칼로리",
                          value: summary.roundedCalories.map { Text("\($0) kcal") } ?? Text(verbatim: "--"))
                MetricRow(icon: "heart.fill", tint: WatchTheme.peach, title: "평균 심박",
                          value: summary.roundedAverageHeartRate.map { Text("\($0) bpm") } ?? Text(verbatim: "--"))
                if let distance = summary.distanceMeters {
                    MetricRow(icon: "location.fill", tint: WatchTheme.mint, title: "거리",
                              value: Text(WorkoutFormat.distance(distance)))
                }
                Label("iPhone 앱이 다음 동기화 때 반영해요", systemImage: "iphone")
                    .font(.watchRounded(.caption2, weight: .regular))
                    .foregroundStyle(WatchTheme.textSecondary)
                    .padding(.top, 2)
                Button("닫기", action: onClose)
                    .tint(WatchTheme.primary)
            }
            .padding(.horizontal, 2)
        }
    }
}

private struct WorkoutFailureView: View {
    let failure: WorkoutFailure
    let onClose: () -> Void

    var body: some View {
        ScrollView {
            VStack(spacing: 8) {
                Image(systemName: "exclamationmark.triangle.fill")
                    .font(.title3)
                    .foregroundStyle(WatchTheme.peach)
                    .accessibilityHidden(true)
                Text(message)
                    .font(.watchRounded(.footnote, weight: .regular))
                    .foregroundStyle(WatchTheme.textPrimary)
                    .multilineTextAlignment(.center)
                Button("닫기", action: onClose)
                    .tint(WatchTheme.primary)
            }
            .frame(maxWidth: .infinity)
        }
    }

    private var message: LocalizedStringKey {
        switch failure {
        case .healthDataUnavailable: "이 기기에서는 건강 데이터를 쓸 수 없어요."
        case .authorizationFailed: "건강 데이터 권한을 확인해 주세요."
        case .startFailed: "운동을 시작하지 못했어요."
        case .saveFailed: "운동을 건강 앱에 저장하지 못했어요."
        case .interrupted: "운동이 중단됐어요."
        }
    }
}

// MARK: - 형식

enum WorkoutFormat {
    /// 0:05:09 → "5:09", 1:02:03 → "1:02:03".
    static func duration(_ seconds: TimeInterval) -> String {
        let total = Int(max(0, seconds))
        let pattern: Duration.TimeFormatStyle.Pattern = total >= 3600 ? .hourMinuteSecond : .minuteSecond
        return Duration.seconds(total).formatted(.time(pattern: pattern))
    }

    /// 1km 미만은 m, 이상은 km(소수 둘째 자리).
    static func distance(_ meters: Double) -> String {
        if meters < 1000 {
            return Measurement(value: meters.rounded(), unit: UnitLength.meters)
                .formatted(.measurement(width: .abbreviated, usage: .asProvided, numberFormatStyle: .number.precision(.fractionLength(0))))
        }
        return Measurement(value: meters / 1000, unit: UnitLength.kilometers)
            .formatted(.measurement(width: .abbreviated, usage: .asProvided, numberFormatStyle: .number.precision(.fractionLength(2))))
    }
}

#if DEBUG
#Preview("운동 중") {
    let model = WorkoutViewModel(controller: DemoWorkoutController())
    return WorkoutSessionView(model: model)
        .task { await model.start(.running) }
}
#endif
