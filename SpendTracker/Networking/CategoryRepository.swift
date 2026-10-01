import Foundation

#if canImport(Supabase)
import Supabase
#endif

/// Repository protocol for pushing categories to the backend.
protocol CategoryRepositoring {
    func upsert(_ category: Category) async throws
    func delete(id: UUID) async throws
    /// Complete owner-scoped snapshot of the `categories` table.
    ///
    /// Implementations MUST derive the user id from the authenticated session
    /// and throw when it doesn't match `ownerId`, and MUST return every row
    /// (paginating past Supabase's default 1,000-row cap). Absence-based
    /// deletion is only sound against a provably complete snapshot.
    func fetchAll(ownerId: String) async throws -> [RemoteCategoryRow]
}

#if canImport(Supabase)
/// Row payload for the `categories` table. Column names match supabase/schema.sql.
private struct CategoryRow: Encodable {
    let id: String
    let user_id: String
    let name: String
    let is_default: Bool
    let sort_order: Int
}
#endif

/// Supabase-backed category repository. Degrades to a no-op when unconfigured.
final class SupabaseCategoryRepository: CategoryRepositoring {
    private let provider = SupabaseClientProvider.shared

    func upsert(_ category: Category) async throws {
        guard provider.isConfigured else { throw AuthError.notConfigured }
        #if canImport(Supabase)
        guard let client = provider.client else { throw AuthError.notConfigured }

        // user_id must come from the authenticated session (RLS is auth.uid()-scoped).
        // Lowercase to match Postgres's canonical uuid text form (auth.uid()).
        let userId: String
        do {
            userId = try await client.auth.session.user.id.uuidString.lowercased()
        } catch {
            guard let stored = client.auth.currentSession else { throw error }
            userId = stored.user.id.uuidString.lowercased()
        }

        let row = CategoryRow(
            id: category.id.uuidString,
            user_id: userId,
            name: category.name,
            is_default: category.isDefault,
            sort_order: category.sortOrder
        )

        try await client
            .from("categories")
            .upsert(row, onConflict: "id")
            .execute()
        #endif
    }

    func delete(id: UUID) async throws {
        guard provider.isConfigured else { throw AuthError.notConfigured }
        #if canImport(Supabase)
        guard let client = provider.client else { throw AuthError.notConfigured }
        try await client
            .from("categories")
            .delete()
            .eq("id", value: id.uuidString)
            .execute()
        #endif
    }

    /// Page size; stays under Supabase's default 1,000-row response cap.
    private static let pageSize = 500

    func fetchAll(ownerId: String) async throws -> [RemoteCategoryRow] {
        guard provider.isConfigured else { throw AuthError.notConfigured }
        #if canImport(Supabase)
        guard let client = provider.client else { throw AuthError.notConfigured }

        let sessionUserId = try await Self.authenticatedUserId(client)
        guard ExpenseOwnership.normalized(ownerId) == sessionUserId else {
            // Never return an empty snapshot for a mismatched owner: callers
            // read absence as deletion.
            throw AuthError.providerUnavailable("Category owner mismatch")
        }

        let decoder = JSONDecoder()
        return try await PaginatedSnapshot.fetchAll(pageSize: Self.pageSize) { from, to in
            let response = try await client
                .from("categories")
                .select()
                .eq("user_id", value: sessionUserId)
                .order("id", ascending: true)
                .range(from: from, to: to)
                .execute()
            return try decoder.decode([RemoteCategoryRow].self, from: response.data)
        }
        #else
        _ = ownerId
        return []
        #endif
    }

    #if canImport(Supabase)
    private static func authenticatedUserId(_ client: SupabaseClient) async throws -> String {
        do {
            return try await client.auth.session.user.id.uuidString.lowercased()
        } catch {
            guard let stored = client.auth.currentSession else { throw error }
            return stored.user.id.uuidString.lowercased()
        }
    }
    #endif
}
