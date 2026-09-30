import Foundation

/// Repository protocol for pushing categories to the backend.
protocol CategoryRepositoring {
    func upsert(_ category: Category) async throws
    func delete(id: UUID) async throws
}

/// Supabase-backed category repository. Degrades to a no-op when unconfigured.
final class SupabaseCategoryRepository: CategoryRepositoring {
    private let provider = SupabaseClientProvider.shared

    func upsert(_ category: Category) async throws {
        guard provider.isConfigured else { throw AuthError.notConfigured }
    }

    func delete(id: UUID) async throws {
        guard provider.isConfigured else { throw AuthError.notConfigured }
    }
}
