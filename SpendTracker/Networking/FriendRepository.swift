import Foundation

#if canImport(Supabase)
import Supabase
#endif

/// Repository protocol for pushing and deleting friends by their local UUID.
protocol FriendRepositoring {
    func upsert(_ friend: Friend) async throws
    func delete(id: UUID) async throws
}

#if canImport(Supabase)
/// Row payload for the `friends` table. `id` is the canonical local/cloud key.
struct FriendUpsertPayload: Encodable {
    let id: String
    let user_id: String
    let name: String
    let they_owe_us: String
    let we_owe_them: String
    let created_at: String
    let updated_at: String
}
#endif

/// Supabase-backed friend repository. It relies on the authenticated session for RLS.
final class SupabaseFriendRepository: FriendRepositoring {
    private let provider = SupabaseClientProvider.shared

    func upsert(_ friend: Friend) async throws {
        guard provider.isConfigured else { throw AuthError.notConfigured }
        #if canImport(Supabase)
        guard let client = provider.client else { throw AuthError.notConfigured }

        let userId: String
        do {
            userId = try await client.auth.session.user.id.uuidString.lowercased()
        } catch {
            guard let stored = client.auth.currentSession else { throw error }
            userId = stored.user.id.uuidString.lowercased()
        }

        // Never upload one account's record under another account's identity.
        guard let ownerId = friend.ownerId?.lowercased(), ownerId == userId else {
            throw AuthError.providerUnavailable("Friend owner mismatch")
        }

        let iso = ISO8601DateFormatter()
        let row = FriendUpsertPayload(
            id: friend.id.uuidString,
            user_id: userId,
            name: friend.name,
            they_owe_us: NSDecimalNumber(decimal: friend.theyOweUs).stringValue,
            we_owe_them: NSDecimalNumber(decimal: friend.weOweThem).stringValue,
            created_at: iso.string(from: friend.createdAt),
            updated_at: iso.string(from: friend.updatedAt)
        )

        try await client
            .from("friends")
            .upsert(row, onConflict: "id")
            .execute()
        #endif
    }

    func delete(id: UUID) async throws {
        guard provider.isConfigured else { throw AuthError.notConfigured }
        #if canImport(Supabase)
        guard let client = provider.client else { throw AuthError.notConfigured }
        try await client
            .from("friends")
            .delete()
            .eq("id", value: id.uuidString)
            .execute()
        #endif
    }
}
