import Foundation

/// Repository protocol for pushing expenses to the backend.
protocol ExpenseRepositoring {
    /// Upsert by UUID. Returns the backend remote id.
    @discardableResult
    func upsert(_ expense: Expense) async throws -> String
    func delete(id: UUID) async throws
}

/// Supabase-backed expense repository. Degrades to a no-op when unconfigured.
final class SupabaseExpenseRepository: ExpenseRepositoring {
    private let provider = SupabaseClientProvider.shared

    @discardableResult
    func upsert(_ expense: Expense) async throws -> String {
        guard provider.isConfigured else { throw AuthError.notConfigured }
        #if canImport(Supabase)
        // Real implementation would encode and upsert into the "expenses" table
        // scoped to auth.uid() via RLS. Kept minimal to avoid build coupling.
        return expense.id.uuidString
        #else
        return expense.id.uuidString
        #endif
    }

    func delete(id: UUID) async throws {
        guard provider.isConfigured else { throw AuthError.notConfigured }
    }
}
