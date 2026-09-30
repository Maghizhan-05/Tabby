import Foundation

#if canImport(Supabase)
import Supabase
#endif

/// A minimal session representation independent of the backend SDK.
struct AuthSession: Equatable {
    let userId: String
    let email: String?
}

enum AuthError: LocalizedError {
    case notConfigured
    case providerUnavailable(String)
    case underlying(String)

    var errorDescription: String? {
        switch self {
        case .notConfigured:
            return "Supabase is not configured. See the README to add SUPABASE_URL and SUPABASE_PUBLISHABLE_KEY."
        case .providerUnavailable(let provider):
            return "\(provider) sign-in is not configured."
        case .underlying(let message):
            return message
        }
    }
}

/// Auth abstraction. Concrete implementation is Supabase-backed, but the app
/// depends only on this protocol so it compiles even if the SDK is absent.
protocol AuthServicing {
    var isSupabaseConfigured: Bool { get }
    var isAppleProviderConfigured: Bool { get }
    var isGoogleProviderConfigured: Bool { get }

    func currentSession() async -> AuthSession?
    func signInEmail(email: String, password: String) async throws -> AuthSession
    func signUpEmail(email: String, password: String) async throws -> AuthSession
    func signInWithApple() async throws -> AuthSession
    func signInWithGoogle() async throws -> AuthSession
    func completeOAuth(from url: URL) async throws -> AuthSession
    func signOut() async throws
}

/// Supabase-backed AuthService. Provider availability is read from config.
final class SupabaseAuthService: AuthServicing {
    private let provider = SupabaseClientProvider.shared

    // Provider configuration flags. In a real deployment these would be driven
    // by Info.plist / remote config; here Supabase presence gates them.
    var isSupabaseConfigured: Bool { provider.isConfigured }
    var isAppleProviderConfigured: Bool {
        provider.isConfigured && Self.infoFlag("APPLE_SIGNIN_ENABLED")
    }
    var isGoogleProviderConfigured: Bool {
        provider.isConfigured && Self.infoFlag("GOOGLE_SIGNIN_ENABLED")
    }

    /// Reads an Info.plist boolean-ish flag. xcconfig substitution delivers these
    /// as strings ("YES"/"NO"/"1"/"true"), so accept both real Bools and strings.
    private static func infoFlag(_ key: String) -> Bool {
        let value = Bundle.main.object(forInfoDictionaryKey: key)
        if let b = value as? Bool { return b }
        if let s = (value as? String)?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() {
            return s == "yes" || s == "true" || s == "1"
        }
        return false
    }

    func currentSession() async -> AuthSession? {
        #if canImport(Supabase)
        guard let client = provider.client else { return nil }
        do {
            let session = try await client.auth.session
            return AuthSession(userId: session.user.id.uuidString, email: session.user.email)
        } catch {
            return nil
        }
        #else
        return nil
        #endif
    }

    func signInEmail(email: String, password: String) async throws -> AuthSession {
        guard provider.isConfigured else { throw AuthError.notConfigured }
        #if canImport(Supabase)
        guard let client = provider.client else { throw AuthError.notConfigured }
        let session = try await client.auth.signIn(email: email, password: password)
        return AuthSession(userId: session.user.id.uuidString, email: session.user.email)
        #else
        throw AuthError.notConfigured
        #endif
    }

    func signUpEmail(email: String, password: String) async throws -> AuthSession {
        guard provider.isConfigured else { throw AuthError.notConfigured }
        #if canImport(Supabase)
        guard let client = provider.client else { throw AuthError.notConfigured }
        let response = try await client.auth.signUp(email: email, password: password)
        return AuthSession(userId: response.user.id.uuidString, email: response.user.email)
        #else
        throw AuthError.notConfigured
        #endif
    }

    func signInWithApple() async throws -> AuthSession {
        guard isAppleProviderConfigured else { throw AuthError.providerUnavailable("Apple") }
        return try await signInWithOAuth(provider: "apple")
    }

    func signInWithGoogle() async throws -> AuthSession {
        guard isGoogleProviderConfigured else { throw AuthError.providerUnavailable("Google") }
        return try await signInWithOAuth(provider: "google")
    }

    /// Custom URL scheme redirect the app declares in Info.plist (spendtracker://).
    static let oauthCallbackURL = URL(string: "spendtracker://auth-callback")

    /// Starts the Supabase OAuth web flow via ASWebAuthenticationSession using the
    /// `spendtracker://auth-callback` redirect. The SDK completes the PKCE round-trip
    /// and returns a Session; a callback opened by the system is also handled in
    /// SpendTrackerApp's `.onOpenURL` via `client.auth.session(from:)`.
    private func signInWithOAuth(provider providerName: String) async throws -> AuthSession {
        #if canImport(Supabase)
        guard let client = self.provider.client else { throw AuthError.notConfigured }
        guard let redirect = Self.oauthCallbackURL else { throw AuthError.notConfigured }
        let oauthProvider: Provider = providerName == "apple" ? .apple : .google
        do {
            let session = try await client.auth.signInWithOAuth(
                provider: oauthProvider,
                redirectTo: redirect
            )
            return AuthSession(userId: session.user.id.uuidString, email: session.user.email)
        } catch {
            throw AuthError.underlying(error.localizedDescription)
        }
        #else
        throw AuthError.providerUnavailable(providerName == "apple" ? "Apple" : "Google")
        #endif
    }

    /// Completes an OAuth round-trip from a callback URL delivered to the app via
    /// the custom URL scheme (spendtracker://auth-callback).
    @discardableResult
    func completeOAuth(from url: URL) async throws -> AuthSession {
        #if canImport(Supabase)
        guard let client = provider.client else { throw AuthError.notConfigured }
        let session = try await client.auth.session(from: url)
        return AuthSession(userId: session.user.id.uuidString, email: session.user.email)
        #else
        throw AuthError.notConfigured
        #endif
    }

    func signOut() async throws {
        #if canImport(Supabase)
        try? await provider.client?.auth.signOut()
        #endif
    }
}
