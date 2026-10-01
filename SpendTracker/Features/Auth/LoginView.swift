import SwiftUI

struct LoginView: View {
    @EnvironmentObject private var auth: AuthViewModel
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var appeared = false

    var body: some View {
        ZStack {
            TabbyBackdrop()
            ScrollView {
                VStack(alignment: .leading, spacing: 26) {
                    header
                    if !auth.isSupabaseConfigured { configBanner }
                    authFields
                    primaryActions
                    divider
                    providerButtons
                    if let notice = auth.notice {
                        Text(notice).font(.footnote).foregroundStyle(Theme.subtleInk).padding(.top, 2)
                    }
                }
                .padding(.horizontal, 24)
                .padding(.top, 64)
                .padding(.bottom, 36)
                .opacity(appeared ? 1 : 0)
                .offset(y: appeared || reduceMotion ? 0 : 14)
            }
        }
        .preferredColorScheme(.dark)
        .tint(Theme.accent)
        .onAppear {
            guard !reduceMotion else { appeared = true; return }
            withAnimation(.spring(response: 0.62, dampingFraction: 0.84)) { appeared = true }
        }
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(spacing: 12) {
                TabbyOrbit(size: 34)
                Text("Tabby")
                    .font(.system(size: 42, weight: .bold, design: .rounded))
                    .foregroundStyle(Theme.ink)
            }
            Text("Keep the tab.\nNot the guilt.")
                .font(.system(.title3, design: .rounded, weight: .medium))
                .foregroundStyle(Theme.subtleInk)
        }
        .padding(.bottom, 8)
    }

    private var configBanner: some View {
        Label("Supabase not configured — see README", systemImage: "exclamationmark.triangle")
            .font(.footnote.weight(.medium))
            .foregroundStyle(Theme.accentBright)
            .padding(14)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Theme.accent.opacity(0.10), in: Theme.controlShape)
            .overlay(Theme.controlShape.stroke(Theme.accent.opacity(0.28)))
    }

    private var authFields: some View {
        VStack(alignment: .leading, spacing: 10) {
            VStack(spacing: 2) {
                TextField("Email", text: $auth.email)
                    .textContentType(.emailAddress)
                    .keyboardType(.emailAddress)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .padding(.horizontal, 18)
                    .padding(.vertical, 17)
                Divider().overlay(Theme.hairline).padding(.horizontal, 18)
                SecureField("Password", text: $auth.password)
                    .textContentType(auth.mode == .createAccount ? .newPassword : .password)
                    .padding(.horizontal, 18)
                    .padding(.vertical, 17)
                if auth.mode == .createAccount {
                    Divider().overlay(Theme.hairline).padding(.horizontal, 18)
                    SecureField("Confirm password", text: $auth.confirmPassword)
                        .textContentType(.newPassword)
                        .padding(.horizontal, 18)
                        .padding(.vertical, 17)
                        .transition(.asymmetric(
                            insertion: .move(edge: .top).combined(with: .opacity),
                            removal: .opacity
                        ))
                }
            }
            .foregroundStyle(Theme.ink)
            .background(Theme.surface.opacity(0.90), in: Theme.cardShape)
            .overlay(Theme.cardShape.stroke(Theme.hairline))
            .clipShape(Theme.cardShape)

            if let error = auth.confirmPasswordError {
                Label(error, systemImage: "exclamationmark.circle.fill")
                    .font(.caption.weight(.medium))
                    .foregroundStyle(Theme.accentBright)
                    .transition(.opacity)
            }

            if auth.mode == .createAccount {
                Text("Create your account — enter email, password, and confirm it.")
                    .font(.caption)
                    .foregroundStyle(Theme.subtleInk)
                    .transition(.opacity)
            }
        }
        .animation(reduceMotion ? nil : .spring(response: 0.42, dampingFraction: 0.82), value: auth.mode)
        .animation(reduceMotion ? nil : .easeInOut(duration: 0.2), value: auth.confirmPasswordError != nil)
    }

    private var primaryActions: some View {
        VStack(spacing: 14) {
            Button { Task { await auth.submitPrimary() } } label: {
                HStack(spacing: 10) {
                    TabbyOrbit(size: 20, lineWidth: 2)
                    Text(auth.primaryCTATitle)
                    Spacer()
                    Image(systemName: "arrow.up.right").font(.subheadline.weight(.bold))
                }
                .font(.body.weight(.semibold))
                .foregroundStyle(Theme.paper)
                .padding(.horizontal, 18)
                .padding(.vertical, 17)
                .background(LinearGradient(colors: [Theme.accentBright, Theme.accent], startPoint: .topLeading, endPoint: .bottomTrailing), in: Theme.controlShape)
                .shadow(color: Theme.accentGlow, radius: 16, y: 6)
                .opacity(auth.canSubmitPrimary ? 1 : 0.5)
            }
            .buttonStyle(.plain)
            .disabled(!auth.canSubmitPrimary)

            Button { withAnimation { auth.toggleMode() } } label: {
                Text(auth.mode == .createAccount
                     ? "Already have an account? Sign in"
                     : "Create an account")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(Theme.accentBright)
            }
            .disabled(auth.isBusy)
        }
    }

    private var divider: some View {
        HStack(spacing: 10) {
            Rectangle().fill(Theme.hairline).frame(height: 1)
            Text("or continue with").font(.caption).foregroundStyle(Theme.subtleInk)
            Rectangle().fill(Theme.hairline).frame(height: 1)
        }
    }

    private var providerButtons: some View {
        VStack(spacing: 10) {
            providerButton(title: "Continue with Google", systemImage: "g.circle", enabled: auth.isGoogleConfigured, disabledText: "Google sign-in not configured") { Task { await auth.signInWithGoogle() } }
        }
    }

    private func providerButton(title: String, systemImage: String, enabled: Bool, disabledText: String, action: @escaping () -> Void) -> some View {
        VStack(alignment: .leading, spacing: 5) {
            Button(action: action) {
                HStack(spacing: 12) {
                    Image(systemName: systemImage).font(.body.weight(.semibold))
                    Text(title).font(.body.weight(.medium))
                    Spacer()
                    Image(systemName: "arrow.up.right").font(.caption.weight(.bold)).foregroundStyle(Theme.subtleInk)
                }
                .foregroundStyle(enabled ? Theme.ink : Theme.subtleInk)
                .padding(.horizontal, 18)
                .padding(.vertical, 16)
                .background(Theme.surface.opacity(0.86), in: Theme.controlShape)
                .overlay(Theme.controlShape.stroke(Theme.hairline))
            }
            .buttonStyle(.plain)
            .disabled(!enabled)
            .opacity(enabled ? 1 : 0.52)
            if !enabled { Text(disabledText).font(.caption2).foregroundStyle(Theme.subtleInk) }
        }
    }
}
