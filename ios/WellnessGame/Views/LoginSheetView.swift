import AuthenticationServices
import SwiftUI

/// 소셜 로그인 시트 — 메인 화면을 단순하게 유지하기 위해 로그인 UI를 분리한다.
/// 로그인은 선택 사항이며, 게스트도 모든 기능을 사용할 수 있다.
struct LoginSheetView: View {
    @Environment(\.dismiss) private var dismiss
    @ObservedObject var userSession: UserSession
    @ObservedObject var socialLogin: SocialLoginService

    var body: some View {
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

            Spacer(minLength: 0)
        }
        .padding(.horizontal, 24)
        .padding(.bottom, 24)
        .background(Theme.background)
        .presentationDetents([.medium])
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
            .disabled(socialLogin.isLoading)
        }
    }

    private var signedOut: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("로그인")
                .font(.system(.title3, design: .rounded).weight(.bold))
                .foregroundStyle(Theme.textPrimary)
            Text("로그인하면 여러 기기에서 같은 캐릭터를 키울 수 있어요. 로그인 없이도 모든 기능을 쓸 수 있습니다.")
                .font(.footnote)
                .foregroundStyle(Theme.textSecondary)

            // 현재 Google 로그인만 지원 — 나머지 제공자는 준비되면 isEnabled를 되돌린다.
            SignInWithAppleButton(.signIn) { request in
                userSession.configureAppleRequest(request)
            } onCompletion: { result in
                userSession.handleAppleResult(result)
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
        .disabled(!isEnabled || socialLogin.isLoading)
        .opacity(isEnabled ? 1 : Self.disabledOpacity)
    }
}
