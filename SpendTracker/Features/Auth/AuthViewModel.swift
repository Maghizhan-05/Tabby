import Foundation
import SwiftUI

@MainActor
final class AuthViewModel: ObservableObject {
    @Published var isAuthenticated = false
    @Published var session: AuthSession?
    @Published var email = ""
    @Published var password = ""
    @Published var isBusy = false
    @Published var notice: String?

    private let authService: AuthServicing

    var isSupabaseConfigured: Bool { authService.isSupabaseConfigured }
    var isAppleConfigured: Bool { authService.isAppleProviderConfigured }
    var isGoogleConfigured: Bool { authService.isGoogleProviderConfigured }

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

    func signInWithApple() async {
        await run {
            let session = try await self.authService.signInWithApple()
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
