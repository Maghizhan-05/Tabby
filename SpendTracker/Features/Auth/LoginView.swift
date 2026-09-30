import SwiftUI

struct LoginView: View {
    @EnvironmentObject private var auth: AuthViewModel

    var body: some View {
        ZStack {
            Theme.paper.ignoresSafeArea()

            ScrollView {
                VStack(alignment: .leading, spacing: 28) {
                    header

                    if !auth.isSupabaseConfigured {
                        configBanner
                    }

                    emailPasswordFields

                    primaryButtons

                    divider

                    providerButtons

                    if let notice = auth.notice {
                        Text(notice)
                            .font(.footnote)
                            .foregroundStyle(Theme.subtleInk)
                            .padding(.top, 4)
                    }
                }
                .padding(.horizontal, 28)
                .padding(.top, 80)
                .padding(.bottom, 40)
            }
        }
        .tint(Theme.accent)
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Tabby")
                .font(.system(size: 44, weight: .bold, design: .rounded))
                .foregroundStyle(Theme.ink)
            Text("Track spending in a tap.")
                .font(.title3)
                .foregroundStyle(Theme.subtleInk)
        }
    }

    private var configBanner: some View {
        Text("Supabase not configured — see README")
            .font(.footnote.weight(.medium))
            .foregroundStyle(Theme.accent)
            .padding(12)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Theme.accent.opacity(0.10), in: RoundedRectangle(cornerRadius: 10))
    }

    private var emailPasswordFields: some View {
        VStack(spacing: 14) {
            TextField("Email", text: $auth.email)
                .textContentType(.emailAddress)
                .keyboardType(.emailAddress)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .padding(14)
                .background(Color.white, in: RoundedRectangle(cornerRadius: 10))
                .overlay(RoundedRectangle(cornerRadius: 10).stroke(Theme.hairline))

            SecureField("Password", text: $auth.password)
                .textContentType(.password)
                .padding(14)
                .background(Color.white, in: RoundedRectangle(cornerRadius: 10))
                .overlay(RoundedRectangle(cornerRadius: 10).stroke(Theme.hairline))
        }
    }

    private var primaryButtons: some View {
        VStack(spacing: 12) {
            Button {
                Task { await auth.signIn() }
            } label: {
                buttonLabel("Sign In")
            }
            .buttonStyle(.plain)
            .disabled(auth.isBusy)

            Button {
                Task { await auth.signUp() }
            } label: {
                Text("Create an account")
                    .font(.subheadline.weight(.medium))
                    .foregroundStyle(Theme.accent)
            }
            .disabled(auth.isBusy)
        }
    }

    private var divider: some View {
        HStack {
            Rectangle().fill(Theme.hairline).frame(height: 1)
            Text("or").font(.caption).foregroundStyle(Theme.subtleInk)
            Rectangle().fill(Theme.hairline).frame(height: 1)
        }
    }

    private var providerButtons: some View {
        VStack(spacing: 12) {
            providerButton(
                title: "Sign in with Apple",
                systemImage: "apple.logo",
                enabled: auth.isAppleConfigured,
                disabledText: "Apple sign-in not configured"
            ) {
                Task { await auth.signInWithApple() }
            }

            providerButton(
                title: "Sign in with Google",
                systemImage: "g.circle",
                enabled: auth.isGoogleConfigured,
                disabledText: "Google sign-in not configured"
            ) {
                Task { await auth.signInWithGoogle() }
            }
        }
    }

    private func providerButton(
        title: String,
        systemImage: String,
        enabled: Bool,
        disabledText: String,
        action: @escaping () -> Void
    ) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Button(action: action) {
                HStack {
                    Image(systemName: systemImage)
                    Text(title).font(.body.weight(.medium))
                    Spacer()
                }
                .padding(14)
                .frame(maxWidth: .infinity)
                .foregroundStyle(enabled ? Theme.ink : Theme.subtleInk)
                .background(Color.white, in: RoundedRectangle(cornerRadius: 10))
                .overlay(RoundedRectangle(cornerRadius: 10).stroke(Theme.hairline))
            }
            .buttonStyle(.plain)
            .disabled(!enabled)
            .opacity(enabled ? 1.0 : 0.55)

            if !enabled {
                Text(disabledText)
                    .font(.caption2)
                    .foregroundStyle(Theme.subtleInk)
            }
        }
    }

    private func buttonLabel(_ title: String) -> some View {
        Text(title)
            .font(.body.weight(.semibold))
            .foregroundStyle(.white)
            .frame(maxWidth: .infinity)
            .padding(16)
            .background(Theme.accent, in: RoundedRectangle(cornerRadius: 12))
    }
}
