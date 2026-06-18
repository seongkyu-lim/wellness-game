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
                VStack(spacing: 18) {
                    header
                    account
                    controls
                    healthSummary
                    characterSummary
                    activityResults
                }
                .padding()
            }
            .background(Color(.systemGroupedBackground))
            .navigationTitle("Wellness Game")
            .task {
                socialLogin.restoreSessionIfNeeded(userSession)
            }
            .overlay {
                if viewModel.isLoading || socialLogin.isLoading {
                    ProgressView()
                        .padding(24)
                        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 16))
                }
            }
        }
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("건강 데이터가 캐릭터 성장으로 이어집니다.")
                .font(.headline)
            Text(viewModel.statusMessage)
                .font(.subheadline)
                .foregroundStyle(.secondary)
            Toggle("Mock 데이터 사용", isOn: $viewModel.useMockData)
                .font(.subheadline)
        }
        .cardStyle()
    }

    private var account: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Label(userSession.accountLabel, systemImage: userSession.isSignedIn ? "person.crop.circle.fill.badge.checkmark" : "person.crop.circle")
                    .font(.headline)
                Spacer()
                Text(userSession.provider?.displayName ?? "비로그인")
                    .font(.caption.bold())
                    .foregroundStyle(userSession.isSignedIn ? .green : .secondary)
            }

            Text(userSession.statusMessage)
                .font(.subheadline)
                .foregroundStyle(.secondary)

            if userSession.isSignedIn {
                Button("로그아웃") {
                    socialLogin.signOut(userSession)
                }
                .buttonStyle(.bordered)
                .disabled(viewModel.isLoading || socialLogin.isLoading)
            } else {
                SignInWithAppleButton(.signIn) { request in
                    userSession.configureAppleRequest(request)
                } onCompletion: { result in
                    userSession.handleAppleResult(result)
                }
                .signInWithAppleButtonStyle(.black)
                .frame(height: 48)
                .disabled(viewModel.isLoading || socialLogin.isLoading)

                Button {
                    socialLogin.signInWithGoogle(userSession)
                } label: {
                    HStack {
                        Text("G")
                            .font(.headline.bold())
                        Text("Google로 로그인")
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 12)
                }
                .buttonStyle(.bordered)
                .tint(.primary)
                .disabled(viewModel.isLoading || socialLogin.isLoading)

                Button {
                    socialLogin.signInWithKakao(userSession)
                } label: {
                    Text("Kakao로 로그인")
                        .fontWeight(.semibold)
                        .foregroundStyle(Color(red: 0.12, green: 0.09, blue: 0.08))
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 12)
                        .background(
                            Color(red: 1.0, green: 0.90, blue: 0.0),
                            in: RoundedRectangle(cornerRadius: 10)
                        )
                }
                .buttonStyle(.plain)
                .disabled(viewModel.isLoading || socialLogin.isLoading)

                Text("소셜 로그인은 선택 사항입니다. 게스트도 건강 데이터 조회와 XP 동기화를 이용할 수 있습니다.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .cardStyle()
    }

    private var controls: some View {
        VStack(spacing: 10) {
            actionButton("HealthKit 권한 요청", systemImage: "heart.text.square") {
                await viewModel.requestAuthorization()
            }
            actionButton("오늘 데이터 불러오기", systemImage: "arrow.clockwise") {
                await viewModel.loadToday()
            }
            actionButton("서버에 동기화", systemImage: "icloud.and.arrow.up") {
                await viewModel.sync()
            }
        }
    }

    @ViewBuilder
    private var healthSummary: some View {
        if let snapshot = viewModel.snapshot {
            VStack(alignment: .leading, spacing: 14) {
                Text("오늘의 활동")
                    .font(.headline)
                metric("걸음 수", value: snapshot.steps.formatted(), icon: "figure.walk")
                metric(
                    "수면",
                    value: snapshot.sleep.map { "\(($0.sleepMinutes ?? 0) / 60)시간 \(($0.sleepMinutes ?? 0) % 60)분" } ?? "기록 없음",
                    icon: "bed.double"
                )
                Divider()
                if snapshot.workouts.isEmpty {
                    Text("오늘 운동 기록이 없습니다.")
                        .foregroundStyle(.secondary)
                } else {
                    ForEach(snapshot.workouts) { workout in
                        HStack {
                            VStack(alignment: .leading) {
                                Text(workout.workoutType?.displayName ?? "운동")
                                    .font(.subheadline.bold())
                                Text("\(workout.durationMinutes ?? 0)분")
                                    .foregroundStyle(.secondary)
                            }
                            Spacer()
                            Text("\(Int(workout.calories ?? 0)) kcal")
                                .font(.subheadline)
                        }
                    }
                }
            }
            .cardStyle()
        }
    }

    @ViewBuilder
    private var characterSummary: some View {
        if let response = viewModel.syncResponse {
            let character = response.character
            VStack(alignment: .leading, spacing: 12) {
                HStack {
                    Text("레벨 \(character.level)")
                        .font(.title2.bold())
                    Spacer()
                    Text("+\(response.gainedXp) XP")
                        .font(.headline)
                        .foregroundStyle(.green)
                }
                ProgressView(
                    value: Double(character.currentXp),
                    total: Double(max(character.nextLevelXp, 1))
                )
                Text("\(character.currentXp) / \(character.nextLevelXp) XP")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                if response.levelUp {
                    Label("레벨업", systemImage: "sparkles")
                        .font(.headline)
                        .foregroundStyle(.orange)
                }
                HStack {
                    stat("STR", character.stats.str)
                    stat("VIT", character.stats.vit)
                    stat("DISC", character.stats.discipline)
                    stat("REC", character.stats.recovery)
                }
            }
            .cardStyle()
        }
    }

    @ViewBuilder
    private var activityResults: some View {
        if let results = viewModel.syncResponse?.activityResults, !results.isEmpty {
            VStack(alignment: .leading, spacing: 10) {
                Text("획득 내역")
                    .font(.headline)
                ForEach(results) { result in
                    HStack {
                        Image(systemName: result.duplicate ? "checkmark.circle" : "plus.circle.fill")
                        Text(result.message)
                        Spacer()
                        Text(result.duplicate ? "반영됨" : "+\(result.gainedXp)")
                            .foregroundStyle(.secondary)
                    }
                    .font(.subheadline)
                }
            }
            .cardStyle()
        }
    }

    private func actionButton(
        _ title: String,
        systemImage: String,
        action: @escaping () async -> Void
    ) -> some View {
        Button {
            Task { await action() }
        } label: {
            Label(title, systemImage: systemImage)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 12)
        }
        .buttonStyle(.borderedProminent)
        .disabled(viewModel.isLoading)
    }

    private func metric(_ title: String, value: String, icon: String) -> some View {
        HStack {
            Label(title, systemImage: icon)
            Spacer()
            Text(value)
                .fontWeight(.semibold)
        }
    }

    private func stat(_ title: String, _ value: Int) -> some View {
        VStack {
            Text(value.formatted())
                .font(.headline)
            Text(title)
                .font(.caption2)
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity)
    }
}

private extension View {
    func cardStyle() -> some View {
        self
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding()
            .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 18))
    }
}

private extension WorkoutType {
    var displayName: String {
        switch self {
        case .swimming: "수영"
        case .running: "달리기"
        case .walking: "걷기"
        case .cycling: "자전거"
        case .strengthTraining: "근력 운동"
        case .other: "운동"
        }
    }
}

#Preview {
    ContentView()
}
