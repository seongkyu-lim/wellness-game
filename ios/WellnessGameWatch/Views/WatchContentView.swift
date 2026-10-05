import SwiftUI

struct WatchContentView: View {
    @ObservedObject var model: WatchDashboardModel
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        NavigationStack {
            Group {
                if model.isSignedIn {
                    SignedInView(model: model)
                } else {
                    SignedOutView()
                }
            }
            .background(WatchTheme.background.ignoresSafeArea())
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
}

// MARK: - 로그인 전

private struct SignedOutView: View {
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
            }
            .frame(maxWidth: .infinity)
            .padding(.horizontal, 4)
        }
    }
}

// MARK: - 로그인 후

private struct SignedInView: View {
    @ObservedObject var model: WatchDashboardModel

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 10) {
                if let snapshot = model.snapshot {
                    CharacterHeader(snapshot: snapshot)
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
