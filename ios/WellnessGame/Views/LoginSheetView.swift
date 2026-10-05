import AuthenticationServices
import SwiftUI

/// 로그인 시트 — 메인 화면을 단순하게 유지하기 위해 로그인 UI를 분리한다.
/// 동기화는 로그인한 사용자(ID/비밀번호 또는 소셜 계정)만 할 수 있다.
struct LoginSheetView: View {
    @Environment(\.dismiss) private var dismiss
    @ObservedObject var userSession: UserSession
    @ObservedObject var socialLogin: SocialLoginService
    @ObservedObject var credentialLogin: CredentialAuthService

    @State private var username = ""
    @State private var password = ""
    @State private var showsOtherProviders = false

    /// 지역별 노출 순서. 시트가 열려 있는 동안 바뀌지 않도록 한 번만 계산한다.
    private let policy: LoginProviderPolicy

    init(
        userSession: UserSession,
        socialLogin: SocialLoginService,
        credentialLogin: CredentialAuthService,
        policy: LoginProviderPolicy = LoginProviderPolicy()
    ) {
        self.userSession = userSession
        self.socialLogin = socialLogin
        self.credentialLogin = credentialLogin
        self.policy = policy
    }

    private var isBusy: Bool {
        socialLogin.isLoading || credentialLogin.isLoading
    }

    private var canSubmitCredentials: Bool {
        !username.trimmingCharacters(in: .whitespaces).isEmpty
            && password.count >= 8
            && !isBusy
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Capsule()
                    .fill(Theme.textSecondary.opacity(0.5))
                    .frame(width: 44, height: 5)
                    .frame(maxWidth: .infinity)
                    .padding(.top, 10)

                if userSession.isSignedIn {
                    signedIn
                } else {
                    signedOut
                }
            }
            .padding(.horizontal, 24)
            .padding(.bottom, 24)
        }
        .background(Theme.background)
        .presentationDetents([.large])
        .presentationDragIndicator(.hidden)
        .onChange(of: userSession.isSignedIn) { _, isSignedIn in
            if isSignedIn {
                dismiss()
            }
        }
    }

    private var signedIn: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("계정")
                .font(.display(.title2))
                .foregroundStyle(Theme.textPrimary)
                .accessibilityAddTraits(.isHeader)
            HStack(spacing: 12) {
                Label(userSession.accountLabel, systemImage: "person.crop.circle.fill.badge.checkmark")
                    .font(.rounded(.subheadline))
                    .foregroundStyle(Theme.textPrimary)
                Spacer(minLength: 0)
                PillBadge(text: userSession.provider?.displayName ?? String(localized: "로그인"), fill: Theme.mint)
            }
            .celCard(radius: 18)

            Button("로그아웃") {
                socialLogin.signOut(userSession)
                dismiss()
            }
            .buttonStyle(.celSecondary)
            .disabled(isBusy)
            .padding(.top, 4)
        }
    }

    private var signedOut: some View {
        VStack(alignment: .leading, spacing: 20) {
            HStack(alignment: .center, spacing: 12) {
                VStack(alignment: .leading, spacing: 6) {
                    Text("로그인")
                        .font(.display(.title))
                        .foregroundStyle(Theme.textPrimary)
                        .accessibilityAddTraits(.isHeader)
                    Text("로그인하면 건강 데이터를 동기화하고 여러 기기에서 같은 캐릭터를 키울 수 있어요.")
                        .font(.rounded(.footnote, weight: .medium))
                        .foregroundStyle(Theme.textSecondary)
                }
                Spacer(minLength: 0)
                CharacterAvatarView(level: GrowthStage.sprout.minLevel, size: 84, decorative: true)
            }

            if let notice = userSession.reloginNotice {
                DangerNotice(text: notice, systemImage: "exclamationmark.circle.fill")
            }

            credentialForm
            divider
            socialButtons
        }
    }

    private var credentialForm: some View {
        VStack(alignment: .leading, spacing: 12) {
            credentialField(
                title: "아이디",
                text: $username,
                prompt: "영문·숫자 4~32자",
                isSecure: false
            )
            .textInputAutocapitalization(.never)
            .autocorrectionDisabled()

            credentialField(
                title: "비밀번호",
                text: $password,
                prompt: "8자 이상",
                isSecure: true
            )

            if let errorMessage = credentialLogin.errorMessage {
                DangerNotice(text: errorMessage)
            }

            Button("로그인") {
                Task { await credentialLogin.logIn(username: username, password: password, into: userSession) }
            }
            .buttonStyle(.celPrimary)
            .disabled(!canSubmitCredentials)
            .opacity(canSubmitCredentials ? 1 : 0.5)
            .padding(.top, 4)

            Button("회원가입") {
                Task { await credentialLogin.signUp(username: username, password: password, into: userSession) }
            }
            .buttonStyle(.celSecondary)
            .disabled(!canSubmitCredentials)
            .opacity(canSubmitCredentials ? 1 : 0.5)
            .padding(.top, 4)
        }
    }

    private func credentialField(
        title: LocalizedStringKey,
        text: Binding<String>,
        prompt: LocalizedStringKey,
        isSecure: Bool
    ) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title)
                .font(.rounded(.subheadline))
                .foregroundStyle(Theme.textPrimary)
            Group {
                if isSecure {
                    SecureField(prompt, text: text)
                } else {
                    TextField(prompt, text: text)
                }
            }
            .textFieldStyle(.plain)
            .font(.rounded(.body, weight: .medium))
            .foregroundStyle(Theme.textPrimary)
            .padding(.horizontal, 14)
            .frame(minHeight: 50)
            .background(RoundedRectangle(cornerRadius: Theme.chipRadius, style: .continuous).fill(Theme.surface))
            .overlay(
                RoundedRectangle(cornerRadius: Theme.chipRadius, style: .continuous)
                    .strokeBorder(Theme.fieldBorder, lineWidth: 2.5)
            )
        }
    }

    private var divider: some View {
        HStack(spacing: 12) {
            Capsule().fill(Theme.fieldBorder.opacity(0.5)).frame(height: 2)
            Text("또는 소셜 계정")
                .font(.rounded(.footnote, weight: .medium))
                .foregroundStyle(Theme.textSecondary)
                .fixedSize()
            Capsule().fill(Theme.fieldBorder.opacity(0.5)).frame(height: 2)
        }
    }

    private var socialButtons: some View {
        VStack(alignment: .leading, spacing: 14) {
            ForEach(policy.primary, id: \.self) { provider in
                providerButton(provider)
            }

            if !policy.secondary.isEmpty {
                Button {
                    withAnimation(.easeInOut(duration: 0.2)) {
                        showsOtherProviders.toggle()
                    }
                } label: {
                    HStack(spacing: 6) {
                        Text("다른 방법으로 로그인")
                        Image(systemName: showsOtherProviders ? "chevron.up" : "chevron.down")
                            .font(.rounded(.caption, weight: .bold))
                            .accessibilityHidden(true)
                    }
                    .font(.rounded(.footnote))
                    .foregroundStyle(Theme.textSecondary)
                    .frame(maxWidth: .infinity, minHeight: 44)
                }
                .buttonStyle(.plain)
                .accessibilityValue(showsOtherProviders ? Text("펼쳐짐") : Text("접힘"))

                if showsOtherProviders {
                    ForEach(policy.secondary, id: \.self) { provider in
                        providerButton(provider)
                    }
                }
            }
        }
    }

    @ViewBuilder
    private func providerButton(_ provider: LoginProvider) -> some View {
        switch provider {
        case .apple:
            SignInWithAppleButton(.signIn) { request in
                request.requestedScopes = [.fullName, .email]
            } onCompletion: { result in
                socialLogin.signInWithApple(result, session: userSession)
            }
            .signInWithAppleButtonStyle(.black)
            .frame(height: 54)
            .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).strokeBorder(Theme.ink, lineWidth: Theme.line))
            .disabled(isBusy)
        case .google:
            socialButton(text: "Google로 로그인", prefix: "G", textColor: Theme.textPrimary, background: Theme.surface) {
                socialLogin.signInWithGoogle(userSession)
            }
        case .kakao:
            socialButton(
                text: "Kakao로 로그인",
                textColor: Theme.onPop,
                background: Color(hex: 0xFEE500)
            ) {
                socialLogin.signInWithKakao(userSession)
            }
        case .naver:
            socialButton(
                text: "Naver로 로그인",
                prefix: "N",
                // 흰 글자는 초록 바탕에서 대비가 부족해 웹과 같이 남색 글자를 쓴다.
                textColor: Color(hex: 0x14172B),
                background: Color(hex: 0x03C75A)
            ) {
                socialLogin.signInWithNaver(userSession)
            }
        case .password:
            EmptyView()
        }
    }

    private func socialButton(
        text: LocalizedStringKey,
        prefix: String? = nil,
        textColor: Color,
        background: Color,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            HStack(spacing: 6) {
                if let prefix {
                    Text(prefix)
                        .font(.display(.headline))
                        .accessibilityHidden(true)
                }
                Text(text)
            }
        }
        .buttonStyle(CelButtonStyle(fill: background, foreground: textColor))
        .disabled(isBusy)
    }
}
