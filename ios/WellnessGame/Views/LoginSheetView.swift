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
                    .fill(Theme.surfaceTint)
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
                .font(.system(.title3, design: .rounded).weight(.bold))
                .foregroundStyle(Theme.textPrimary)
            HStack {
                Label(userSession.accountLabel, systemImage: "person.crop.circle.fill.badge.checkmark")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(Theme.textPrimary)
                Spacer()
                PillBadge(text: userSession.provider?.displayName ?? "로그인", color: Theme.primary)
            }
            .padding(16)
            .background(Theme.surface, in: RoundedRectangle(cornerRadius: 16, style: .continuous))

            Button("로그아웃") {
                socialLogin.signOut(userSession)
                dismiss()
            }
            .buttonStyle(SecondaryActionButtonStyle())
            .disabled(isBusy)
        }
    }

    private var signedOut: some View {
        VStack(alignment: .leading, spacing: 20) {
            VStack(alignment: .leading, spacing: 6) {
                Text("로그인")
                    .font(.system(.title3, design: .rounded).weight(.bold))
                    .foregroundStyle(Theme.textPrimary)
                Text("로그인하면 건강 데이터를 동기화하고 여러 기기에서 같은 캐릭터를 키울 수 있어요.")
                    .font(.footnote)
                    .foregroundStyle(Theme.textSecondary)
            }

            if let notice = userSession.reloginNotice {
                Label(notice, systemImage: "exclamationmark.circle.fill")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(Theme.statStrength)
                    .padding(12)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(Theme.surface, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
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
                    .font(.caption)
                    .foregroundStyle(Theme.statStrength)
            }

            Button("로그인") {
                Task { await credentialLogin.logIn(username: username, password: password, into: userSession) }
            }
            .buttonStyle(PrimaryActionButtonStyle())
            .disabled(!canSubmitCredentials)
            .opacity(canSubmitCredentials ? 1 : 0.5)

            Button("회원가입") {
                Task { await credentialLogin.signUp(username: username, password: password, into: userSession) }
            }
            .buttonStyle(SecondaryActionButtonStyle())
            .disabled(!canSubmitCredentials)
            .opacity(canSubmitCredentials ? 1 : 0.5)
        }
    }

    private func credentialField(
        title: String,
        text: Binding<String>,
        prompt: String,
        isSecure: Bool
    ) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title)
                .font(.caption.weight(.semibold))
                .foregroundStyle(Theme.textSecondary)
            Group {
                if isSecure {
                    SecureField(prompt, text: text)
                } else {
                    TextField(prompt, text: text)
                }
            }
            .textFieldStyle(.plain)
            .padding(.horizontal, 14)
            .padding(.vertical, 12)
            .background(Theme.surface, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        }
    }

    private var divider: some View {
        HStack(spacing: 12) {
            Rectangle().fill(Theme.surfaceTint).frame(height: 1)
            Text("또는 소셜 계정")
                .font(.caption)
                .foregroundStyle(Theme.textSecondary)
                .fixedSize()
            Rectangle().fill(Theme.surfaceTint).frame(height: 1)
        }
    }

    private var socialButtons: some View {
        VStack(alignment: .leading, spacing: 12) {
            // 현재 Google 로그인만 지원 — 나머지 제공자는 준비되면 isEnabled를 되돌린다.
            SignInWithAppleButton(.signIn) { request in
                request.requestedScopes = [.fullName, .email]
            } onCompletion: { _ in
                userSession.updateStatus("Apple 로그인은 아직 지원하지 않습니다.")
            }
            .signInWithAppleButtonStyle(.black)
            .frame(height: 48)
            .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
            .disabled(true)
            .opacity(Self.disabledOpacity)

            socialButton(text: "Google로 로그인", prefix: "G", textColor: Theme.textPrimary, background: Theme.surface) {
                socialLogin.signInWithGoogle(userSession)
            }
            socialButton(
                text: "Kakao로 로그인",
                textColor: Color(red: 0.12, green: 0.09, blue: 0.08),
                background: Color(red: 1.0, green: 0.90, blue: 0.0),
                isEnabled: false
            ) {
                socialLogin.signInWithKakao(userSession)
            }
            socialButton(
                text: "Naver로 로그인",
                prefix: "N",
                textColor: .white,
                background: Color(red: 0.01, green: 0.78, blue: 0.35),
                isEnabled: false
            ) {
                socialLogin.signInWithNaver(userSession)
            }
        }
    }

    private static let disabledOpacity = 0.4

    private func socialButton(
        text: String,
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
        .disabled(!isEnabled || isBusy)
        .opacity(isEnabled ? 1 : Self.disabledOpacity)
    }
}
