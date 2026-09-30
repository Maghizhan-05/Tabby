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
            return "Supabase is not configured. See the README to add SUPABASE_URL and SUPABASE_ANON_KEY."
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
    func signOut() async throws
}

/// Supabase-backed AuthService. Provider availability is read from config.
final class SupabaseAuthService: AuthServicing {
    private let provider = SupabaseClientProvider.shared

    // Provider configuration flags. In a real deployment these would be driven
    // by Info.plist / remote config; here Supabase presence gates them.
    var isSupabaseConfigured: Bool { provider.isConfigured }
    var isAppleProviderConfigured: Bool {
        provider.isConfigured &&
        (Bundle.main.object(forInfoDictionaryKey: "APPLE_SIGNIN_ENABLED") as? Bool ?? false)
    }
    var isGoogleProviderConfigured: Bool {
        provider.isConfigured &&
        (Bundle.main.object(forInfoDictionaryKey: "GOOGLE_SIGNIN_ENABLED") as? Bool ?? false)
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
        // OAuth flow wiring lives here in a real deployment.
        throw AuthError.providerUnavailable("Apple")
    }

    func signInWithGoogle() async throws -> AuthSession {
        guard isGoogleProviderConfigured else { throw AuthError.providerUnavailable("Google") }
        // OAuth flow wiring lives here in a real deployment.
        throw AuthError.providerUnavailable("Google")
    }

    func signOut() async throws {
        #if canImport(Supabase)
        try? await provider.client?.auth.signOut()
        #endif
    }
}
