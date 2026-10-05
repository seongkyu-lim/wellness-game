import SwiftUI

struct ContentView: View {
    @Environment(\.scenePhase) private var scenePhase
    @StateObject private var userSession: UserSession
    @StateObject private var socialLogin: SocialLoginService
    @StateObject private var credentialLogin: CredentialAuthService
    @StateObject private var viewModel: DashboardViewModel
    @State private var showLoginSheet = false

    init() {
        // 세션과 네트워크가 같은 Keychain 저장소를 공유해야 로그인 직후 토큰이 헤더에 실린다.
        let tokenStore = KeychainTokenStore()
        let networkClient = NetworkClient(tokenStore: tokenStore)
        let userSession = UserSession(tokenStore: tokenStore)
        _userSession = StateObject(wrappedValue: userSession)
        _socialLogin = StateObject(wrappedValue: SocialLoginService(networkClient: networkClient))
        _credentialLogin = StateObject(wrappedValue: CredentialAuthService(networkClient: networkClient))
        _viewModel = StateObject(wrappedValue: DashboardViewModel(networkClient: networkClient, userSession: userSession))
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: Theme.sectionSpacing) {
                    header
                    characterHero
                    characterDetails
                    healthSummary
                    activityResults
                    footer
                }
                .padding(.horizontal, 18)
                .padding(.vertical, 12)
            }
            .background(Theme.background)
            .toolbar(.hidden, for: .navigationBar)
            .refreshable {
                await viewModel.autoSync(force: true)
            }
            .task {
                await viewModel.autoSync()
            }
            .onChange(of: scenePhase) { _, phase in
                if phase == .active {
                    Task { await viewModel.autoSync() }
                }
            }
            .onChange(of: viewModel.useMockData) {
                // autoSync는 로그인하지 않았으면 동기화하지 않는다.
                Task { await viewModel.autoSync(force: true) }
            }
            .onChange(of: userSession.userId) { _, userId in
                if userId != nil {
                    Task { await viewModel.autoSync(force: true) }
                } else {
                    viewModel.reset()
                    // 401·만료로 세션이 끊기면 로그인 시트를 다시 띄워 재로그인을 안내한다.
                    if userSession.reloginNotice != nil {
                        showLoginSheet = true
                    }
                }
            }
            .sheet(isPresented: $showLoginSheet) {
                LoginSheetView(userSession: userSession, socialLogin: socialLogin, credentialLogin: credentialLogin)
            }
            .overlay {
                if viewModel.isLoading || socialLogin.isLoading || credentialLogin.isLoading {
                    ProgressView()
                        .controlSize(.large)
                        .tint(Theme.textPrimary)
                        .padding(24)
                        .celOutline(radius: 18, shadow: 4)
                }
            }
        }
    }

    // MARK: - Header

    private var header: some View {
        HStack(alignment: .center, spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                (Text(verbatim: "Wellness ") + Text(verbatim: "Game").foregroundStyle(Theme.primary))
                    .font(.display(.title))
                    .foregroundStyle(Theme.textPrimary)
                    .accessibilityAddTraits(.isHeader)
                Text(Date.now.formatted(.dateTime.month(.wide).day().weekday(.wide)))
                    .font(.rounded(.subheadline, weight: .medium))
                    .foregroundStyle(Theme.textSecondary)
            }
            Spacer(minLength: 0)
            Button {
                showLoginSheet = true
            } label: {
                if userSession.isSignedIn {
                    Label(userSession.provider?.displayName ?? String(localized: "계정"), systemImage: "person.crop.circle.fill.badge.checkmark")
                } else {
                    Label("로그인", systemImage: "person.fill")
                }
            }
            .buttonStyle(
                CelButtonStyle(
                    fill: Theme.surface,
                    foreground: Theme.textPrimary,
                    height: 44,
                    radius: 22,
                    shadow: CGSize(width: 3, height: 3),
                    font: .rounded(.subheadline, weight: .bold),
                    expands: false
                )
            )
        }
        .padding(.top, 8)
    }

    // MARK: - Character hero

    private var characterHero: some View {
        let character = viewModel.syncResponse?.character
        let level = character?.level ?? 1
        let bubble: String
        if !userSession.isSignedIn {
            bubble = String(localized: "로그인하면 나도 깨어날게!")
        } else if character == nil {
            bubble = String(localized: "동기화하면 나도 깨어날게!")
        } else if let next = GrowthStage.next(after: level) {
            bubble = String(localized: "Lv.\(next.minLevel)이 되면 \(next.displayName) 단계로 자랄 거야!")
        } else {
            bubble = String(localized: "활짝 피었어! 늘 함께해 줘서 고마워!")
        }

        return CharacterHero(
            level: level,
            bubble: bubble,
            levelChip: character.map {
                String(localized: "Lv.\($0.level) · \(GrowthStage.stage(for: $0.level).displayName) 단계")
            } ?? String(localized: "아직 동기화 전"),
            gainedXp: viewModel.syncResponse?.gainedXp ?? 0,
            levelUp: viewModel.syncResponse?.levelUp ?? false
        )
    }

    // MARK: - EXP · stats (또는 로그인/동기화 안내)

    @ViewBuilder
    private var characterDetails: some View {
        if !userSession.isSignedIn {
            signInPrompt
        } else if let character = viewModel.syncResponse?.character {
            XPCard(character: character)
            HStack(spacing: 8) {
                StatTile(title: "STR", subtitle: "근력", value: character.stats.str, icon: "dumbbell.fill", color: Theme.statStrength)
                StatTile(title: "VIT", subtitle: "활력", value: character.stats.vit, icon: "heart.fill", color: Theme.statVitality)
                StatTile(title: "DISC", subtitle: "절제", value: character.stats.discipline, icon: "target", color: Theme.statDiscipline)
                StatTile(title: "REC", subtitle: "회복", value: character.stats.recovery, icon: "moon.zzz.fill", color: Theme.statRecovery)
            }
        } else {
            waitingCard
        }
    }

    /// 로그인 전 상태 — 동기화하지 않고 로그인 시트로 유도한다.
    private var signInPrompt: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("로그인하고 새싹이를 키워 보세요")
                .font(.display(.title3))
                .foregroundStyle(Theme.textPrimary)
            if let notice = userSession.reloginNotice {
                Label(notice, systemImage: "exclamationmark.circle.fill")
                    .font(.rounded(.footnote))
                    .foregroundStyle(Theme.dangerText)
                    .padding(12)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .celOutline(radius: Theme.chipRadius, fill: Theme.dangerBackground)
            } else {
                Text("로그인하면 걸음 · 운동 · 수면이 자동으로 XP가 되어\n캐릭터가 자라나요")
                    .font(.rounded(.footnote, weight: .medium))
                    .foregroundStyle(Theme.textSecondary)
            }
            Button("로그인하기") {
                showLoginSheet = true
            }
            .buttonStyle(.celPrimary)
            .padding(.top, 4)
            .padding(.bottom, 5)
        }
        .celCard()
    }

    /// 로그인했지만 아직 동기화 결과가 없는 상태.
    private var waitingCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("새싹이가 기다리고 있어요")
                .font(.display(.title3))
                .foregroundStyle(Theme.textPrimary)
            Text("걸음 · 운동 · 수면이 자동으로 XP가 되어\n캐릭터가 자라나요")
                .font(.rounded(.footnote, weight: .medium))
                .foregroundStyle(Theme.textSecondary)
            HStack(spacing: 8) {
                PillBadge.muted(String(localized: "Lv.\(1) · \(GrowthStage.seed.displayName) 단계"))
                if let next = GrowthStage.next(after: 1) {
                    PillBadge(text: String(localized: "다음 단계 · \(next.displayName) Lv.\(next.minLevel)"), fill: Theme.mint)
                }
            }
        }
        .celCard()
    }

    // MARK: - Health summary (오늘의 퀘스트)

    @ViewBuilder
    private var healthSummary: some View {
        if let snapshot = viewModel.snapshot {
            VStack(alignment: .leading, spacing: 10) {
                SectionHeader(title: "오늘의 퀘스트")
                QuestCard(
                    icon: "figure.walk",
                    tint: Theme.mint,
                    title: String(localized: "걸음 수"),
                    subtitle: String(localized: "\(snapshot.steps.formatted())걸음")
                )
                QuestCard(icon: "moon.zzz.fill", tint: Theme.lilac, title: String(localized: "수면"), subtitle: snapshot.sleepDurationText)
                if snapshot.workouts.isEmpty {
                    QuestCard(
                        icon: "figure.walk.motion",
                        tint: Theme.surface,
                        title: String(localized: "오늘 운동 기록이 없어요"),
                        subtitle: String(localized: "가볍게 몸을 움직여 볼까요?")
                    )
                } else {
                    ForEach(snapshot.workouts) { workout in
                        QuestCard(
                            icon: workout.workoutType?.iconName ?? "figure.mixed.cardio",
                            tint: Theme.peach,
                            title: workout.workoutType?.displayName ?? String(localized: "운동"),
                            subtitle: String(localized: "\(workout.durationMinutes ?? 0)분")
                        ) {
                            PillBadge.muted("\(Int(workout.calories ?? 0).formatted()) kcal")
                        }
                    }
                }
            }
        }
    }

    // MARK: - Activity results (획득 내역)

    @ViewBuilder
    private var activityResults: some View {
        if let results = viewModel.syncResponse?.activityResults, !results.isEmpty {
            VStack(alignment: .leading, spacing: 10) {
                SectionHeader(title: "획득 내역")
                ForEach(results) { result in
                    QuestCard(
                        icon: result.duplicate ? "checkmark" : "sparkles",
                        tint: result.duplicate ? Theme.surface : Theme.yellow,
                        title: result.message
                    ) {
                        if result.duplicate {
                            PillBadge.muted(String(localized: "반영됨"))
                        } else {
                            PillBadge(text: "+\(result.gainedXp.formatted()) XP")
                        }
                    }
                }
            }
        }
    }

    // MARK: - Footer (상태 메시지 · 개발자 옵션)

    private var footer: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(viewModel.statusMessage)
                .font(.rounded(.caption, weight: .medium))
                .foregroundStyle(Theme.textSecondary)
            Toggle(isOn: $viewModel.useMockData) {
                Label("Mock 데이터 사용", systemImage: "wrench.and.screwdriver")
                    .font(.rounded(.footnote, weight: .medium))
                    .foregroundStyle(Theme.textSecondary)
            }
            .tint(Theme.primary)
            .disabled(!userSession.isSignedIn)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 4)
        .padding(.top, 4)
        .padding(.bottom, 8)
    }
}

// MARK: - Hero

/// 하늘(하프톤 점) + 언덕 + 말풍선 + 반짝이 + 둥실거리는 새싹이 + 이름표.
private struct CharacterHero: View {
    let level: Int
    let bubble: String
    let levelChip: String
    let gainedXp: Int
    let levelUp: Bool

    private var hasBadges: Bool { levelUp || gainedXp > 0 }

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: 24, style: .continuous)
        HeroBackdrop()
            .frame(height: 372)
            .overlay(alignment: .top) {
                CharacterAvatarView(level: level, size: 210)
                    .bobbing()
                    .padding(.top, 92)
            }
            .overlay(alignment: .topLeading) {
                SparkleView(size: 18, color: Theme.sparklePink, period: 1.0)
                    .padding(.leading, 48)
                    .padding(.top, 150)
            }
            .overlay(alignment: .topTrailing) {
                SparkleView(size: 16, color: .white, period: 1.15)
                    .padding(.trailing, 78)
                    .padding(.top, 84)
            }
            .overlay(alignment: .topTrailing) {
                if hasBadges {
                    VStack(alignment: .trailing, spacing: 6) {
                        if levelUp {
                            PillBadge(text: String(localized: "레벨업!"))
                        }
                        if gainedXp > 0 {
                            PillBadge(text: "+\(gainedXp.formatted()) XP", fill: Theme.xp)
                        }
                    }
                    .padding([.top, .trailing], 16)
                } else {
                    SparkleView(size: 26, color: Theme.yellow, period: 0.9)
                        .padding(.trailing, 34)
                        .padding(.top, 26)
                }
            }
            .overlay(alignment: .topLeading) {
                SpeechBubble(text: bubble)
                    .frame(maxWidth: 200, alignment: .leading)
                    .padding(16)
            }
            .overlay(alignment: .bottom) {
                HStack(spacing: 8) {
                    Text("새싹이")
                        .font(.display(.title3))
                        .foregroundStyle(Theme.onPop)
                        .padding(.horizontal, 14)
                        .padding(.vertical, 4)
                        .celOutline(radius: 12, fill: Theme.yellow, lineWidth: 2.5)
                        .skewedX(degrees: -8)
                    Spacer(minLength: 0)
                    Text(levelChip)
                        .font(.rounded(.subheadline))
                        .foregroundStyle(Theme.textPrimary)
                        .lineLimit(1)
                        .minimumScaleFactor(0.8)
                        .padding(.horizontal, 12)
                        .padding(.vertical, 5)
                        .background(Capsule().fill(Theme.surface))
                        .overlay(Capsule().strokeBorder(Theme.ink, lineWidth: Theme.line))
                }
                .padding(.horizontal, 16)
                .padding(.bottom, 14)
            }
            .clipShape(shape)
            .overlay(shape.strokeBorder(Theme.ink, lineWidth: Theme.line))
            .background(shape.fill(Theme.popShadow).offset(x: 6, y: 6))
            .accessibilityElement(children: .contain)
            .accessibilityLabel("내 캐릭터")
    }
}

/// 하늘색 바탕 + 흰 하프톤 점 + 두 겹 언덕.
private struct HeroBackdrop: View {
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        ZStack(alignment: .bottom) {
            Theme.sky
            Canvas { context, size in
                var dots = Path()
                for x in stride(from: CGFloat(6), to: size.width, by: 12) {
                    for y in stride(from: CGFloat(6), to: size.height, by: 12) {
                        dots.addEllipse(in: CGRect(x: x - 1.6, y: y - 1.6, width: 3.2, height: 3.2))
                    }
                }
                context.fill(dots, with: .color(.white.opacity(colorScheme == .dark ? 0.22 : 0.55)))
            }
            ZStack {
                HillShape(kind: .back)
                    .fill(Theme.hill)
                    .overlay(HillShape(kind: .back).stroke(Theme.ink, style: StrokeStyle(lineWidth: Theme.line, lineJoin: .round)))
                HillShape(kind: .front)
                    .fill(Theme.hillDeep)
            }
            .frame(height: 110)
        }
        .accessibilityHidden(true)
    }
}

/// 웹 CharacterCard.tsx의 언덕 경로(viewBox 390×110, preserveAspectRatio none)를 영역 크기에 맞춰 늘린다.
private struct HillShape: Shape {
    enum Kind { case back, front }
    let kind: Kind

    func path(in rect: CGRect) -> Path {
        let sx = rect.width / 390, sy = rect.height / 110
        func p(_ x: CGFloat, _ y: CGFloat) -> CGPoint { CGPoint(x: rect.minX + x * sx, y: rect.minY + y * sy) }

        var path = Path()
        switch kind {
        case .back:
            // M0 52 Q70 24 150 46 T300 40 T390 50 V110 H0Z (T의 제어점은 직전 제어점의 반사점)
            path.move(to: p(0, 52))
            path.addQuadCurve(to: p(150, 46), control: p(70, 24))
            path.addQuadCurve(to: p(300, 40), control: p(230, 68))
            path.addQuadCurve(to: p(390, 50), control: p(370, 12))
        case .front:
            // M0 74 Q90 58 190 72 T390 70 V110 H0Z
            path.move(to: p(0, 74))
            path.addQuadCurve(to: p(190, 72), control: p(90, 58))
            path.addQuadCurve(to: p(390, 70), control: p(290, 86))
        }
        path.addLine(to: p(390, 110))
        path.addLine(to: p(0, 110))
        path.closeSubpath()
        return path
    }
}

/// 꼬리 달린 말풍선.
private struct SpeechBubble: View {
    let text: String

    var body: some View {
        Text(text)
            .font(.rounded(.subheadline))
            .foregroundStyle(Theme.textPrimary)
            .lineLimit(4)
            .minimumScaleFactor(0.8)
            .fixedSize(horizontal: false, vertical: true)
            .padding(.horizontal, 14)
            .padding(.vertical, 10)
            .celOutline(radius: 18)
            .overlay(alignment: .bottomLeading) {
                BubbleTail()
                    .frame(width: 26, height: 20)
                    .offset(x: 31, y: 16.5)
                    .accessibilityHidden(true)
            }
    }
}

/// 말풍선 꼬리 — 위쪽 테두리를 카드색으로 덮어 말풍선과 이어 보이게 한다.
private struct BubbleTail: View {
    var body: some View {
        Canvas { context, _ in
            // 꼬리 좌표계의 y=0이 말풍선 테두리 선의 중심(캔버스 y=2)에 오도록 옮긴다.
            context.translateBy(x: 0, y: 2)
            var cover = Path(CGRect(x: 3, y: -1.6, width: 18, height: 3.2))
            cover.move(to: CGPoint(x: 2, y: 0))
            cover.addLine(to: CGPoint(x: 12, y: 16))
            cover.addLine(to: CGPoint(x: 22, y: 0))
            cover.closeSubpath()
            context.fill(cover, with: .color(Theme.surface))

            var edge = Path()
            edge.move(to: CGPoint(x: 2, y: 0))
            edge.addLine(to: CGPoint(x: 12, y: 16))
            edge.addLine(to: CGPoint(x: 22, y: 0))
            context.stroke(edge, with: .color(Theme.ink), style: StrokeStyle(lineWidth: Theme.line, lineCap: .round, lineJoin: .round))
        }
    }
}

// MARK: - EXP card

private struct XPCard: View {
    let character: CharacterState

    private var nextLabel: String {
        GrowthStage.next(after: character.level).map { String(localized: "\($0.displayName)까지 Lv.\($0.minLevel)") } ?? String(localized: "최종 단계")
    }

    var body: some View {
        let xpText = "\(character.currentXp.formatted()) / \(character.nextLevelXp.formatted()) XP"
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 8) {
                Text(verbatim: "EXP")
                    .font(.display(.title2))
                    .tracking(1)
                    .foregroundStyle(Theme.textPrimary)
                    .accessibilityHidden(true)
                Spacer(minLength: 0)
                PillBadge(text: String(localized: "누적 \(character.totalXp.formatted()) XP"), fill: Theme.pink)
            }
            XPBarView(progress: character.xpProgress)
                .accessibilityElement()
                .accessibilityLabel("다음 레벨까지 경험치")
                .accessibilityValue(xpText)
            HStack(spacing: 8) {
                Text(xpText)
                    .foregroundStyle(Theme.textPrimary)
                    .contentTransition(.numericText())
                Spacer(minLength: 0)
                Text(nextLabel)
                    .foregroundStyle(Theme.textSecondary)
            }
            .font(.rounded(.subheadline))
            .lineLimit(1)
            .minimumScaleFactor(0.8)
        }
        .celCard(padding: 16, radius: 20)
    }
}

#Preview {
    ContentView()
}
