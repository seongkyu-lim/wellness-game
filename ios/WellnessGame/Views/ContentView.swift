import AuthenticationServices
import SwiftUI

struct ContentView: View {
    @StateObject private var userSession: UserSession
    @StateObject private var socialLogin: SocialLoginService
    @StateObject private var viewModel: DashboardViewModel

    init() {
        let userSession = UserSession()
        _userSession = StateObject(wrappedValue: userSession)
        _socialLogin = StateObject(wrappedValue: SocialLoginService())
        _viewModel = StateObject(wrappedValue: DashboardViewModel(userSession: userSession))
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: Theme.sectionSpacing) {
                    header
                    characterHero
                    actions
                    healthSummary
                    activityResults
                    account
                    developerOptions
                }
                .padding(.horizontal, 20)
                .padding(.vertical, 12)
            }
            .background(Theme.background)
            .toolbar(.hidden, for: .navigationBar)
            .task {
                socialLogin.restoreSessionIfNeeded(userSession)
            }
            .overlay {
                if viewModel.isLoading || socialLogin.isLoading {
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
        VStack(alignment: .leading, spacing: 4) {
            Text(Date.now.formatted(.dateTime.locale(Locale(identifier: "ko_KR")).month(.wide).day().weekday(.wide)))
                .font(.caption.weight(.semibold))
                .foregroundStyle(Theme.textSecondary)
            Text("오늘도 한 뼘 성장해요 🌱")
                .font(.system(.title2, design: .rounded).weight(.bold))
                .foregroundStyle(Theme.textPrimary)
            Text(viewModel.statusMessage)
                .font(.footnote)
                .foregroundStyle(Theme.textSecondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.top, 8)
    }

    // MARK: - Character hero

    private var characterHero: some View {
        VStack(alignment: .leading, spacing: 16) {
            if let response = viewModel.syncResponse {
                let character = response.character
                HStack(spacing: 20) {
                    XPRingView(
                        level: character.level,
                        progress: Double(character.currentXp) / Double(max(character.nextLevelXp, 1))
                    )
                    VStack(alignment: .leading, spacing: 8) {
                        Text("내 캐릭터")
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(Theme.textSecondary)
                        Text("\(character.currentXp.formatted()) / \(character.nextLevelXp.formatted()) XP")
                            .font(.system(.headline, design: .rounded).weight(.bold))
                            .foregroundStyle(Theme.textPrimary)
                        HStack(spacing: 8) {
                            if response.gainedXp > 0 {
                                PillBadge(text: "+\(response.gainedXp) XP")
                            }
                            if response.levelUp {
                                PillBadge(text: "레벨업! ✨", color: Theme.amber)
                            }
                        }
                    }
                    Spacer(minLength: 0)
                }
                HStack(spacing: 8) {
                    StatTile(title: "STR", subtitle: "근력", value: character.stats.str, icon: "dumbbell.fill", color: Theme.statStrength)
                    StatTile(title: "VIT", subtitle: "활력", value: character.stats.vit, icon: "heart.fill", color: Theme.statVitality)
                    StatTile(title: "DISC", subtitle: "절제", value: character.stats.discipline, icon: "target", color: Theme.statDiscipline)
                    StatTile(title: "REC", subtitle: "회복", value: character.stats.recovery, icon: "moon.zzz.fill", color: Theme.statRecovery)
                }
            } else {
                HStack(spacing: 16) {
                    Image(systemName: "leaf.circle.fill")
                        .font(.system(size: 44))
                        .foregroundStyle(Theme.primary)
                    VStack(alignment: .leading, spacing: 4) {
                        Text("캐릭터가 기다리고 있어요")
                            .font(.headline)
                            .foregroundStyle(Theme.textPrimary)
                        Text("건강 데이터를 동기화하면 XP를 얻고 캐릭터가 성장해요.")
                            .font(.footnote)
                            .foregroundStyle(Theme.textSecondary)
                    }
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(18)
        .background(Theme.heroGradient, in: RoundedRectangle(cornerRadius: Theme.cardRadius, style: .continuous))
        .shadow(color: .black.opacity(0.06), radius: 12, y: 5)
    }

    // MARK: - Actions

    private var actions: some View {
        VStack(spacing: 10) {
            Button {
                Task { await viewModel.sync() }
            } label: {
                Label("서버에 동기화", systemImage: "icloud.and.arrow.up")
            }
            .buttonStyle(PrimaryActionButtonStyle())
            .disabled(viewModel.isLoading)

            HStack(spacing: 10) {
                Button {
                    Task { await viewModel.requestAuthorization() }
                } label: {
                    Label("HealthKit 권한", systemImage: "heart.text.square")
                }
                .buttonStyle(SecondaryActionButtonStyle())
                .disabled(viewModel.isLoading)

                Button {
                    Task { await viewModel.loadToday() }
                } label: {
                    Label("오늘 데이터", systemImage: "arrow.clockwise")
                }
                .buttonStyle(SecondaryActionButtonStyle())
                .disabled(viewModel.isLoading)
            }
        }
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
                        value: snapshot.sleep.map { "\(($0.sleepMinutes ?? 0) / 60)시간 \(($0.sleepMinutes ?? 0) % 60)분" } ?? "기록 없음",
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

    // MARK: - Account

    private var account: some View {
        VStack(alignment: .leading, spacing: 12) {
            SectionHeader(title: "계정", icon: "person.crop.circle")
            VStack(alignment: .leading, spacing: 12) {
                HStack {
                    Label(userSession.accountLabel, systemImage: userSession.isSignedIn ? "person.crop.circle.fill.badge.checkmark" : "person.crop.circle")
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(Theme.textPrimary)
                    Spacer()
                    PillBadge(
                        text: userSession.provider?.displayName ?? "비로그인",
                        color: userSession.isSignedIn ? Theme.primary : Theme.textSecondary
                    )
                }

                Text(userSession.statusMessage)
                    .font(.footnote)
                    .foregroundStyle(Theme.textSecondary)

                if userSession.isSignedIn {
                    Button("로그아웃") {
                        socialLogin.signOut(userSession)
                    }
                    .buttonStyle(SecondaryActionButtonStyle())
                    .disabled(viewModel.isLoading || socialLogin.isLoading)
                } else {
                    SignInWithAppleButton(.signIn) { request in
                        userSession.configureAppleRequest(request)
                    } onCompletion: { result in
                        userSession.handleAppleResult(result)
                    }
                    .signInWithAppleButtonStyle(.black)
                    .frame(height: 48)
                    .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                    .disabled(viewModel.isLoading || socialLogin.isLoading)

                    socialButton(
                        text: "Google로 로그인",
                        prefix: "G",
                        textColor: Theme.textPrimary,
                        background: Theme.surfaceTint
                    ) {
                        socialLogin.signInWithGoogle(userSession)
                    }

                    socialButton(
                        text: "Kakao로 로그인",
                        textColor: Color(red: 0.12, green: 0.09, blue: 0.08),
                        background: Color(red: 1.0, green: 0.90, blue: 0.0)
                    ) {
                        socialLogin.signInWithKakao(userSession)
                    }

                    socialButton(
                        text: "Naver로 로그인",
                        prefix: "N",
                        textColor: .white,
                        background: Color(red: 0.01, green: 0.78, blue: 0.35)
                    ) {
                        socialLogin.signInWithNaver(userSession)
                    }

                    Text("소셜 로그인은 선택 사항입니다. 게스트도 건강 데이터 조회와 XP 동기화를 이용할 수 있습니다.")
                        .font(.caption)
                        .foregroundStyle(Theme.textSecondary)
                }
            }
            .wellnessCard()
        }
    }

    private func socialButton(
        text: String,
        prefix: String? = nil,
        textColor: Color,
        background: Color,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            HStack(spacing: 6) {
                if let prefix {
                    Text(prefix)
                        .font(.headline.bold())
                }
                Text(text)
                    .fontWeight(.semibold)
            }
            .foregroundStyle(textColor)
            .frame(maxWidth: .infinity)
            .frame(height: 48)
            .background(background, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        }
        .buttonStyle(.plain)
        .disabled(viewModel.isLoading || socialLogin.isLoading)
    }

    // MARK: - Developer options

    private var developerOptions: some View {
        Toggle(isOn: $viewModel.useMockData) {
            Label("Mock 데이터 사용", systemImage: "wrench.and.screwdriver")
                .font(.footnote)
                .foregroundStyle(Theme.textSecondary)
        }
        .tint(Theme.primary)
        .padding(.horizontal, 4)
        .padding(.bottom, 8)
    }
}

#Preview {
    ContentView()
}
