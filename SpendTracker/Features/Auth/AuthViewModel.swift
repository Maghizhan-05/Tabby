import Foundation
import SwiftUI

/// Whether the email/password form is signing into an existing account or
/// creating a new one. Drives the confirm-password reveal and CTA label.
enum AuthMode {
    case signIn
    case createAccount
}

@MainActor
final class AuthViewModel: ObservableObject {
    @Published var isAuthenticated = false
    @Published var session: AuthSession?
    @Published var email = ""
    @Published var password = ""
    @Published var confirmPassword = ""
    @Published var mode: AuthMode = .signIn
    @Published var isBusy = false
    @Published var notice: String?

    private let authService: AuthServicing

    var isSupabaseConfigured: Bool { authService.isSupabaseConfigured }
    var isGoogleConfigured: Bool { authService.isGoogleProviderConfigured }

    // MARK: - Form validation

    /// A minimally valid email: non-empty, contains "@" with text either side
    /// and a dot in the domain. Deliberately lenient — the server is the source
    /// of truth; this only gates the CTA so obvious typos don't submit.
    var isEmailValid: Bool {
        let trimmed = email.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let at = trimmed.firstIndex(of: "@"), at != trimmed.startIndex else { return false }
        let domain = trimmed[trimmed.index(after: at)...]
        return domain.contains(".") && !domain.hasPrefix(".") && !domain.hasSuffix(".")
    }

    /// Supabase requires passwords of at least 6 characters by default.
    var isPasswordValid: Bool { password.count >= 6 }

    /// In create mode the confirm field must match; only meaningful once both
    /// fields have content so we don't flash an error on an empty form.
    var passwordsMatch: Bool { password == confirmPassword }

    /// Inline, theme-styled validation message for the create-account form, or
    /// nil when the form is submittable. Only surfaces once the user has typed a
    /// confirm value so it doesn't nag an untouched field.
    var confirmPasswordError: String? {
        guard mode == .createAccount, !confirmPassword.isEmpty, !passwordsMatch else { return nil }
        return "Passwords don't match."
    }

    /// Whether the primary CTA should be enabled for the current mode.
    var canSubmitPrimary: Bool {
        guard !isBusy, isEmailValid, isPasswordValid else { return false }
        if mode == .createAccount { return passwordsMatch && !confirmPassword.isEmpty }
        return true
    }

    /// Label for the primary CTA, switching by mode.
    var primaryCTATitle: String {
        if isBusy { return mode == .createAccount ? "Creating account" : "Signing in" }
        return mode == .createAccount ? "Create Account" : "Enter Tabby"
    }

    /// Toggles between sign-in and create-account modes, clearing transient state.
    func toggleMode() {
        mode = (mode == .signIn) ? .createAccount : .signIn
        notice = nil
        if mode == .signIn { confirmPassword = "" }
    }

    /// Runs the correct auth call for the current mode.
    func submitPrimary() async {
        switch mode {
        case .signIn: await signIn()
        case .createAccount: await signUp()
        }
    }

    init(authService: AuthServicing = SupabaseAuthService()) {
        self.authService = authService
        Task { await restoreSession() }
    }

    func restoreSession() async {
        if let existing = await authService.currentSession() {
            self.session = existing
            self.isAuthenticated = true
        }
    }

    func signIn() async {
        await run {
            let session = try await self.authService.signInEmail(
                email: self.email, password: self.password
            )
            self.session = session
            self.isAuthenticated = true
        }
    }

    func signUp() async {
        await run {
            let session = try await self.authService.signUpEmail(
                email: self.email, password: self.password
            )
            self.session = session
            self.isAuthenticated = true
        }
    }


    func signInWithGoogle() async {
        await run {
            let session = try await self.authService.signInWithGoogle()
            self.session = session
            self.isAuthenticated = true
        }
    }

    /// Completes an OAuth round-trip when the callback URL is delivered to the app
    /// via the custom URL scheme (spendtracker://auth-callback).
    func handleOAuthCallback(_ url: URL) async {
        await run {
            let session = try await self.authService.completeOAuth(from: url)
            self.session = session
            self.isAuthenticated = true
        }
    }

    func signOut() async {
        try? await authService.signOut()
        session = nil
        isAuthenticated = false
        email = ""
        password = ""
        confirmPassword = ""
        mode = .signIn
    }

    private func run(_ operation: @escaping () async throws -> Void) async {
        isBusy = true
        notice = nil
        defer { isBusy = false }
        do {
            try await operation()
        } catch {
            notice = error.localizedDescription
        }
    }
}
