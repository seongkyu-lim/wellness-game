import AuthenticationServices
import SwiftUI

/// 로그인 시트 — 메인 화면을 단순하게 유지하기 위해 로그인 UI를 분리한다.
/// 동기화는 로그인한 사용자(ID/비밀번호 또는 Google)만 할 수 있다.
struct LoginSheetView: View {
    @Environment(\.dismiss) private var dismiss
    @ObservedObject var userSession: UserSession
    @ObservedObject var socialLogin: SocialLoginService
    @ObservedObject var credentialLogin: CredentialAuthService

    @State private var username = ""
    @State private var password = ""

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
                Label(notice, systemImage: "exclamationmark.circle.fill")
                    .font(.rounded(.footnote))
                    .foregroundStyle(Theme.dangerText)
                    .padding(12)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .celOutline(radius: Theme.chipRadius, fill: Theme.dangerBackground)
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
                Text(errorMessage)
                    .font(.rounded(.footnote))
                    .foregroundStyle(Theme.dangerText)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 8)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .celOutline(radius: Theme.chipRadius, fill: Theme.dangerBackground, lineWidth: 2.5)
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
            // 현재 Google 로그인만 지원 — 나머지 제공자는 준비되면 isEnabled를 되돌린다.
            SignInWithAppleButton(.signIn) { request in
                request.requestedScopes = [.fullName, .email]
            } onCompletion: { _ in
                userSession.updateStatus(String(localized: "Apple 로그인은 아직 지원하지 않습니다."))
            }
            .signInWithAppleButtonStyle(.black)
            .frame(height: 54)
            .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).strokeBorder(Theme.ink, lineWidth: Theme.line))
            .disabled(true)
            .opacity(Self.disabledOpacity)

            socialButton(text: "Google로 로그인", prefix: "G", textColor: Theme.textPrimary, background: Theme.surface) {
                socialLogin.signInWithGoogle(userSession)
            }
            socialButton(
                text: "Kakao로 로그인",
                textColor: Theme.onPop,
                background: Color(light: 0xFEE500, dark: 0xFEE500),
                isEnabled: false
            ) {
                socialLogin.signInWithKakao(userSession)
            }
            socialButton(
                text: "Naver로 로그인",
                prefix: "N",
                // 흰 글자는 초록 바탕에서 대비가 부족해 웹과 같이 남색 글자를 쓴다.
                textColor: Color(light: 0x14172B, dark: 0x14172B),
                background: Color(light: 0x03C75A, dark: 0x03C75A),
                isEnabled: false
            ) {
                socialLogin.signInWithNaver(userSession)
            }
        }
    }

    private static let disabledOpacity = 0.4

    private func socialButton(
        text: LocalizedStringKey,
        prefix: String? = nil,
        textColor: Color,
        background: Color,
        isEnabled: Bool = true,
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
        .disabled(!isEnabled || isBusy)
        .opacity(isEnabled ? 1 : Self.disabledOpacity)
    }
}
