import SwiftUI

struct WatchContentView: View {
    @ObservedObject var model: WatchDashboardModel
    @ObservedObject var workout: WorkoutViewModel
    @Environment(\.scenePhase) private var scenePhase
    @State private var showsWorkoutPicker = false

    var body: some View {
        Group {
            // 운동이 시작되면(복구 포함) 홈 대신 세션 화면을 보여 주고, 요약·오류 화면을 닫으면 홈으로 돌아온다.
            // 시스템 닫기 버튼이 생기는 fullScreenCover 대신 직접 바꿔 끼워 운동 중에 실수로 닫히지 않게 한다.
            if workout.phase != .idle {
                WorkoutSessionView(model: workout)
            } else {
                home
            }
        }
        .task {
            await model.refresh()
        }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active {
                Task { await model.refresh() }
            }
        }
    }

    private var home: some View {
        NavigationStack {
            Group {
                if model.isSignedIn {
                    SignedInView(model: model, onStartWorkout: openWorkoutPicker)
                } else {
                    // 로그인 전에도 운동 기록(HealthKit 저장)은 할 수 있다. XP 반영은 iPhone 로그인 후 동기화 때.
                    SignedOutView(onStartWorkout: openWorkoutPicker)
                }
            }
            .background(WatchTheme.background.ignoresSafeArea())
            .navigationDestination(isPresented: $showsWorkoutPicker) {
                WorkoutKindPicker { kind in
                    showsWorkoutPicker = false
                    Task { await workout.start(kind) }
                }
            }
        }
    }

    private func openWorkoutPicker() {
        showsWorkoutPicker = true
    }
}

// MARK: - 로그인 전

private struct SignedOutView: View {
    let onStartWorkout: () -> Void

    var body: some View {
        ScrollView {
            VStack(spacing: 10) {
                CharacterAvatarView(level: GrowthStage.sprout.minLevel, size: 64, decorative: true)
                Text("iPhone에서 로그인하세요")
                    .font(.watchDisplay(.headline))
                    .foregroundStyle(WatchTheme.textPrimary)
                    .multilineTextAlignment(.center)
                Text("iPhone의 Wellness Game 앱에서 로그인하면 여기서 새싹이를 볼 수 있어요.")
                    .font(.watchRounded(.footnote, weight: .regular))
                    .foregroundStyle(WatchTheme.textSecondary)
                    .multilineTextAlignment(.center)
                StartWorkoutButton(action: onStartWorkout)
                    .padding(.top, 4)
            }
            .frame(maxWidth: .infinity)
            .padding(.horizontal, 4)
        }
    }
}

// MARK: - 로그인 후

private struct SignedInView: View {
    @ObservedObject var model: WatchDashboardModel
    let onStartWorkout: () -> Void

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 10) {
                if let snapshot = model.snapshot {
                    CharacterHeader(snapshot: snapshot)
                    StartWorkoutButton(action: onStartWorkout)
                    QuestList(quests: snapshot.quests)
                    if model.isSnapshotStale() {
                        Label("업데이트 필요", systemImage: "exclamationmark.arrow.circlepath")
                            .font(.watchRounded(.footnote))
                            .foregroundStyle(WatchTheme.yellow)
                    }
                    Text("마지막 동기화 \(snapshot.syncedAt.formatted(date: .omitted, time: .shortened))")
                        .font(.watchRounded(.caption2, weight: .regular))
                        .foregroundStyle(WatchTheme.textSecondary)
                } else {
                    placeholder
                    StartWorkoutButton(action: onStartWorkout)
                }
                statusMessage
                Button {
                    Task { await model.refresh() }
                } label: {
                    if model.isLoading {
                        ProgressView()
                    } else {
                        Label("새로고침", systemImage: "arrow.clockwise")
                    }
                }
                .tint(WatchTheme.primary)
                .disabled(model.isLoading)
            }
            .padding(.horizontal, 2)
        }
        .navigationTitle("새싹이")
    }

    @ViewBuilder
    private var placeholder: some View {
        if model.isLoading {
            HStack {
                ProgressView()
                Text("불러오는 중…")
                    .foregroundStyle(WatchTheme.textSecondary)
            }
        }
    }

    @ViewBuilder
    private var statusMessage: some View {
        switch model.status {
        case .noCharacter:
            Text("아직 캐릭터가 없어요. iPhone에서 먼저 동기화해 주세요.")
                .font(.watchRounded(.footnote, weight: .regular))
                .foregroundStyle(WatchTheme.textSecondary)
        case .failed:
            Text("서버에 연결하지 못했어요.")
                .font(.watchRounded(.footnote, weight: .regular))
                .foregroundStyle(WatchTheme.peach)
        case .idle, .loading:
            EmptyView()
        }
    }
}

private struct CharacterHeader: View {
    let snapshot: CharacterSnapshot

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 8) {
                CharacterAvatarView(level: snapshot.level, size: 52)
                VStack(alignment: .leading, spacing: 2) {
                    Text(snapshot.stage.displayName)
                        .font(.watchDisplay(.headline))
                        .foregroundStyle(WatchTheme.textPrimary)
                    Text("Lv.\(snapshot.level)")
                        .font(.watchRounded(.footnote))
                        .foregroundStyle(WatchTheme.xp)
                }
            }
            WatchProgressBar(progress: snapshot.xpProgress)
            Text("\(snapshot.currentXp) / \(snapshot.nextLevelXp) XP")
                .font(.watchRounded(.caption2, weight: .regular))
                .foregroundStyle(WatchTheme.textSecondary)
        }
        .accessibilityElement(children: .combine)
    }
}

private struct QuestList: View {
    let quests: DailyQuestProgress

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("오늘의 퀘스트")
                .font(.watchDisplay(.footnote))
                .foregroundStyle(WatchTheme.primary)
            QuestRow(
                icon: "figure.walk",
                tint: WatchTheme.mint,
                title: Text("걸음 수"),
                value: Text(verbatim: "\(quests.steps.formatted()) / \(DailyQuestProgress.stepsGoal.formatted())"),
                progress: quests.stepsProgress
            )
            QuestRow(
                icon: "figure.run",
                tint: WatchTheme.peach,
                title: Text("운동"),
                value: Text("\(quests.workoutMinutes) / \(DailyQuestProgress.workoutGoalMinutes)분"),
                progress: quests.workoutProgress
            )
            QuestRow(
                icon: "moon.zzz.fill",
                tint: WatchTheme.lilac,
                title: Text("수면"),
                value: Text("\(quests.sleepMinutes) / \(DailyQuestProgress.sleepGoalMinutes)분"),
                progress: quests.sleepProgress
            )
        }
    }
}

private struct QuestRow: View {
    let icon: String
    let tint: Color
    let title: Text
    let value: Text
    let progress: Double

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 6) {
                Image(systemName: icon)
                    .font(.caption)
                    .foregroundStyle(WatchTheme.onPop)
                    .frame(width: 22, height: 22)
                    .background(Circle().fill(tint))
                    .accessibilityHidden(true)
                title
                    .font(.watchRounded(.footnote))
                    .foregroundStyle(WatchTheme.textPrimary)
                Spacer(minLength: 4)
                value
                    .font(.watchRounded(.caption2, weight: .regular))
                    .foregroundStyle(WatchTheme.textSecondary)
                    .monospacedDigit()
            }
            WatchProgressBar(progress: progress, tint: tint, height: 5)
        }
        .padding(8)
        .background(RoundedRectangle(cornerRadius: WatchTheme.cardRadius).fill(WatchTheme.surface))
        .accessibilityElement(children: .combine)
    }
}
