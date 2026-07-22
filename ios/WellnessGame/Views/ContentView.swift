import SwiftUI

struct ContentView: View {
    @Environment(\.scenePhase) private var scenePhase
    @StateObject private var userSession: UserSession
    @StateObject private var socialLogin: SocialLoginService
    @StateObject private var credentialLogin: CredentialAuthService
    @StateObject private var viewModel: DashboardViewModel
    @State private var showLoginSheet = false

    init() {
        let userSession = UserSession()
        _userSession = StateObject(wrappedValue: userSession)
        _socialLogin = StateObject(wrappedValue: SocialLoginService())
        _credentialLogin = StateObject(wrappedValue: CredentialAuthService())
        _viewModel = StateObject(wrappedValue: DashboardViewModel(userSession: userSession))
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: Theme.sectionSpacing) {
                    header
                    characterHero
                    healthSummary
                    activityResults
                    footer
                }
                .padding(.horizontal, 20)
                .padding(.vertical, 12)
            }
            .background(Theme.background)
            .toolbar(.hidden, for: .navigationBar)
            .refreshable {
                await viewModel.autoSync(force: true)
            }
            .task {
                socialLogin.restoreSessionIfNeeded(userSession)
                await viewModel.autoSync()
            }
            .onChange(of: scenePhase) { _, phase in
                if phase == .active {
                    Task { await viewModel.autoSync() }
                }
            }
            .onChange(of: viewModel.useMockData) {
                Task { await viewModel.autoSync(force: true) }
            }
            .sheet(isPresented: $showLoginSheet) {
                LoginSheetView(userSession: userSession, socialLogin: socialLogin, credentialLogin: credentialLogin)
            }
            .overlay {
                if viewModel.isLoading || socialLogin.isLoading || credentialLogin.isLoading {
                    ProgressView()
                        .tint(Theme.primary)
                        .padding(24)
                        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                }
            }
        }
    }

    // MARK: - Header

    private var header: some View {
        HStack(alignment: .center) {
            VStack(alignment: .leading, spacing: 2) {
                Text("Wellness Game")
                    .font(.system(.title2, design: .rounded).weight(.bold))
                    .foregroundStyle(Theme.textPrimary)
                Text(Date.now.formatted(.dateTime.locale(Locale(identifier: "ko_KR")).month(.wide).day().weekday(.wide)))
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(Theme.textSecondary)
            }
            Spacer()
            Button {
                showLoginSheet = true
            } label: {
                if userSession.isSignedIn {
                    Label(userSession.provider?.displayName ?? "계정", systemImage: "person.crop.circle.fill.badge.checkmark")
                        .font(.footnote.weight(.semibold))
                } else {
                    Text("로그인")
                        .font(.footnote.weight(.semibold))
                }
            }
            .foregroundStyle(Theme.primary)
            .padding(.horizontal, 14)
            .padding(.vertical, 8)
            .background(Theme.surfaceTint, in: Capsule())
        }
        .padding(.top, 8)
    }

    // MARK: - Character hero

    private var characterHero: some View {
        VStack(spacing: 18) {
            if let response = viewModel.syncResponse {
                let character = response.character
                XPRingView(level: character.level, progress: character.xpProgress, size: 158, lineWidth: 13)
                    .padding(.top, 6)

                VStack(spacing: 8) {
                    HStack(spacing: 8) {
                        PillBadge(text: "Lv.\(character.level) · \(GrowthStage.stage(for: character.level).displayName) 단계")
                        if response.gainedXp > 0 {
                            PillBadge(text: "+\(response.gainedXp) XP", color: Theme.primary)
                        }
                        if response.levelUp {
                            PillBadge(text: "레벨업! ✨", color: Theme.amber)
                        }
                    }
                    Text("\(character.currentXp.formatted()) / \(character.nextLevelXp.formatted()) XP")
                        .font(.system(.title3, design: .rounded).weight(.bold))
                        .foregroundStyle(Theme.textPrimary)
                    Text(
                        GrowthStage.next(after: character.level).map {
                            "Lv.\($0.minLevel)이 되면 \($0.displayName) 단계로 자라나요"
                        } ?? "마지막 단계까지 모두 자랐어요 🌸"
                    )
                    .font(.caption)
                    .foregroundStyle(Theme.textSecondary)
                }

                HStack(spacing: 8) {
                    StatTile(title: "STR", subtitle: "근력", value: character.stats.str, icon: "dumbbell.fill", color: Theme.statStrength)
                    StatTile(title: "VIT", subtitle: "활력", value: character.stats.vit, icon: "heart.fill", color: Theme.statVitality)
                    StatTile(title: "DISC", subtitle: "절제", value: character.stats.discipline, icon: "target", color: Theme.statDiscipline)
                    StatTile(title: "REC", subtitle: "회복", value: character.stats.recovery, icon: "moon.zzz.fill", color: Theme.statRecovery)
                }
            } else {
                VStack(spacing: 22) {
                    CharacterAvatarView(level: 1, size: 190)
                    VStack(spacing: 8) {
                        Text("새싹이가 기다리고 있어요")
                            .font(.system(.title3, design: .rounded).weight(.bold))
                            .foregroundStyle(Theme.textPrimary)
                        Text("걸음 · 운동 · 수면이 자동으로 XP가 되어\n캐릭터가 자라나요")
                            .font(.footnote)
                            .foregroundStyle(Theme.textSecondary)
                            .multilineTextAlignment(.center)
                    }
                    HStack(spacing: 8) {
                        PillBadge(text: "Lv.1 · \(GrowthStage.seed.displayName) 단계")
                        if let next = GrowthStage.next(after: 1) {
                            PillBadge(text: "다음 단계 · \(next.displayName) Lv.\(next.minLevel)", color: Theme.textSecondary)
                        }
                    }
                    .padding(.bottom, 12)
                }
            }
        }
        .frame(maxWidth: .infinity, minHeight: viewModel.syncResponse == nil ? 460 : 0)
        .padding(20)
        .background(Theme.heroGradient, in: RoundedRectangle(cornerRadius: Theme.cardRadius, style: .continuous))
        .shadow(color: .black.opacity(0.06), radius: 12, y: 5)
    }

    // MARK: - Health summary

    @ViewBuilder
    private var healthSummary: some View {
        if let snapshot = viewModel.snapshot {
            VStack(alignment: .leading, spacing: 12) {
                SectionHeader(title: "오늘의 활동", icon: "sun.max.fill")
                HStack(spacing: 12) {
                    MetricCard(
                        title: "걸음 수",
                        value: snapshot.steps.formatted(),
                        icon: "figure.walk",
                        color: Theme.statVitality
                    )
                    MetricCard(
                        title: "수면",
                        value: snapshot.sleepDurationText,
                        icon: "moon.zzz.fill",
                        color: Theme.statRecovery
                    )
                }
                workoutList
            }
        }
    }

    @ViewBuilder
    private var workoutList: some View {
        VStack(alignment: .leading, spacing: 14) {
            if let workouts = viewModel.snapshot?.workouts, !workouts.isEmpty {
                ForEach(workouts) { workout in
                    HStack(spacing: 12) {
                        Image(systemName: workout.workoutType?.iconName ?? "figure.mixed.cardio")
                            .font(.headline)
                            .foregroundStyle(Theme.primary)
                            .frame(width: 38, height: 38)
                            .background(Theme.surfaceTint, in: RoundedRectangle(cornerRadius: Theme.chipRadius, style: .continuous))
                        VStack(alignment: .leading, spacing: 2) {
                            Text(workout.workoutType?.displayName ?? "운동")
                                .font(.subheadline.weight(.semibold))
                                .foregroundStyle(Theme.textPrimary)
                            Text("\(workout.durationMinutes ?? 0)분")
                                .font(.caption)
                                .foregroundStyle(Theme.textSecondary)
                        }
                        Spacer()
                        PillBadge(text: "\(Int(workout.calories ?? 0)) kcal", color: Theme.statStrength)
                    }
                }
            } else {
                HStack(spacing: 10) {
                    Image(systemName: "figure.walk.motion")
                        .foregroundStyle(Theme.textSecondary)
                    Text("오늘 운동 기록이 없어요. 가볍게 몸을 움직여 볼까요?")
                        .font(.footnote)
                        .foregroundStyle(Theme.textSecondary)
                }
            }
        }
        .wellnessCard()
    }

    // MARK: - Activity results

    @ViewBuilder
    private var activityResults: some View {
        if let results = viewModel.syncResponse?.activityResults, !results.isEmpty {
            VStack(alignment: .leading, spacing: 12) {
                SectionHeader(title: "획득 내역", icon: "sparkles")
                VStack(spacing: 12) {
                    ForEach(results) { result in
                        HStack(spacing: 10) {
                            Image(systemName: result.duplicate ? "checkmark.circle.fill" : "plus.circle.fill")
                                .foregroundStyle(result.duplicate ? Theme.textSecondary : Theme.lime)
                            Text(result.message)
                                .font(.subheadline)
                                .foregroundStyle(Theme.textPrimary)
                            Spacer()
                            if result.duplicate {
                                Text("반영됨")
                                    .font(.caption)
                                    .foregroundStyle(Theme.textSecondary)
                            } else {
                                PillBadge(text: "+\(result.gainedXp) XP")
                            }
                        }
                    }
                }
                .wellnessCard()
            }
        }
    }

    // MARK: - Footer (상태 메시지 · 개발자 옵션)

    private var footer: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(viewModel.statusMessage)
                .font(.caption2)
                .foregroundStyle(Theme.textSecondary)
            Toggle(isOn: $viewModel.useMockData) {
                Label("Mock 데이터 사용", systemImage: "wrench.and.screwdriver")
                    .font(.footnote)
                    .foregroundStyle(Theme.textSecondary)
            }
            .tint(Theme.primary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 4)
        .padding(.bottom, 8)
    }
}

#Preview {
    ContentView()
}
