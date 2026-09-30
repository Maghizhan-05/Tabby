import Foundation

#if canImport(Supabase)
import Supabase

/// TEST-ONLY in-memory `AuthLocalStorage`.
///
/// Lives in the TEST TARGET ONLY — it is never compiled into the app. The
/// production `SupabaseClientProvider` builds its client with the SDK's default
/// secure `KeychainLocalStorage`; this exists solely so the XCTest host — which
/// cannot reliably persist to the Keychain — can hold the auth session in a
/// plain dictionary for the duration of a test run. No tokens are written to
/// disk, UserDefaults, or any App Group.
final class InMemoryAuthStorage: AuthLocalStorage, @unchecked Sendable {
    private let lock = NSLock()
    private var store: [String: Data] = [:]

    func store(key: String, value: Data) throws {
        lock.lock(); defer { lock.unlock() }
        store[key] = value
    }

    func retrieve(key: String) throws -> Data? {
        lock.lock(); defer { lock.unlock() }
        return store[key]
    }

    func remove(key: String) throws {
        lock.lock(); defer { lock.unlock() }
        store.removeValue(forKey: key)
    }
}
#endif
