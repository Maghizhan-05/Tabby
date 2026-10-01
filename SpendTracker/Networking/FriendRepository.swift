import Foundation

#if canImport(Supabase)
import Supabase
#endif

/// Repository protocol for pushing and deleting friends by their local UUID.
protocol FriendRepositoring {
    func upsert(_ friend: Friend) async throws
    func delete(id: UUID) async throws
    /// Every remote row for `ownerId`, as a COMPLETE snapshot.
    ///
    /// Same contract as `ExpenseRepositoring.fetchAll`: verify `ownerId`
    /// against the authenticated session and page until a short page proves
    /// completeness. MUST throw rather than return a partial list — a truncated
    /// snapshot would look like mass deletion to `FriendReconciliation`.
    func fetchAll(ownerId: String) async throws -> [RemoteFriendRow]
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

    /// Rows requested per page; Supabase caps an unpaged `select` at 1000.
    static let pageSize = 500

    /// The authenticated session's user id, lowercased to match `auth.uid()`.
    private func sessionUserId() async throws -> String {
        #if canImport(Supabase)
        guard let client = provider.client else { throw AuthError.notConfigured }
        do {
            return try await client.auth.session.user.id.uuidString.lowercased()
        } catch {
            guard let stored = client.auth.currentSession else { throw error }
            return stored.user.id.uuidString.lowercased()
        }
        #else
        throw AuthError.notConfigured
        #endif
    }

    func upsert(_ friend: Friend) async throws {
        guard provider.isConfigured else { throw AuthError.notConfigured }
        #if canImport(Supabase)
        guard let client = provider.client else { throw AuthError.notConfigured }

        let userId = try await sessionUserId()

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

    /// A complete, owner-verified snapshot of the account's remote friends.
    /// Mirrors `SupabaseExpenseRepository.fetchAll`: the owner is taken from the
    /// authenticated session (a mismatching request throws rather than
    /// returning an empty list that would erase local rows), and pages are
    /// requested in a deterministic `id` order until a short page proves the
    /// snapshot is complete.
    func fetchAll(ownerId: String) async throws -> [RemoteFriendRow] {
        guard provider.isConfigured else { throw AuthError.notConfigured }
        guard let requested = ExpenseOwnership.normalized(ownerId) else {
            throw AuthError.providerUnavailable("Friend fetch requires an owner")
        }
        #if canImport(Supabase)
        guard let client = provider.client else { throw AuthError.notConfigured }

        let sessionUser = try await sessionUserId()
        guard requested == sessionUser else {
            throw AuthError.providerUnavailable("Friend owner mismatch")
        }

        let decoder = JSONDecoder()
        return try await PaginatedSnapshot.fetchAll(pageSize: Self.pageSize) { from, to in
            let response = try await client
                .from("friends")
                .select()
                .eq("user_id", value: sessionUser)
                .order("id", ascending: true)
                .range(from: from, to: to)
                .execute()
            return try decoder.decode([RemoteFriendRow].self, from: response.data)
        }
        #else
        _ = requested
        return []
        #endif
    }
}
