import Foundation

#if canImport(Supabase)
import Supabase
#endif

/// Repository protocol for pushing categories to the backend.
protocol CategoryRepositoring {
    func upsert(_ category: Category) async throws
    func delete(id: UUID) async throws
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

        let session = try await client.auth.session
        let userId = session.user.id.uuidString

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
}
