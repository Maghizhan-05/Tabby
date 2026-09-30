import Foundation

#if canImport(Supabase)
import Supabase
#endif

/// Repository protocol for pushing expenses to the backend.
protocol ExpenseRepositoring {
    /// Upsert by UUID. Returns the backend remote id.
    @discardableResult
    func upsert(_ expense: Expense) async throws -> String
    func delete(id: UUID) async throws
}

#if canImport(Supabase)
/// Row payload for the `expenses` table. Column names match supabase/schema.sql.
private struct ExpenseRow: Encodable {
    let id: String
    let user_id: String
    let amount: String        // numeric(12,2) — send as string to preserve precision
    let category_name: String
    let date: String          // ISO8601 timestamptz
    let created_at: String
    let updated_at: String
}
#endif

/// Supabase-backed expense repository. Degrades to a no-op when unconfigured.
final class SupabaseExpenseRepository: ExpenseRepositoring {
    private let provider = SupabaseClientProvider.shared

    @discardableResult
    func upsert(_ expense: Expense) async throws -> String {
        guard provider.isConfigured else { throw AuthError.notConfigured }
        #if canImport(Supabase)
        guard let client = provider.client else { throw AuthError.notConfigured }

        // user_id must come from the authenticated session (RLS is auth.uid()-scoped).
        let session = try await client.auth.session
        let userId = session.user.id.uuidString

        let iso = ISO8601DateFormatter()
        let row = ExpenseRow(
            id: expense.id.uuidString,
            user_id: userId,
            amount: NSDecimalNumber(decimal: expense.amount).stringValue,
            category_name: expense.categoryName,
            date: iso.string(from: expense.date),
            created_at: iso.string(from: expense.createdAt),
            updated_at: iso.string(from: Date())
        )

        // Upsert by primary key (id) so re-runs are idempotent.
        try await client
            .from("expenses")
            .upsert(row, onConflict: "id")
            .execute()

        return expense.id.uuidString
        #else
        return expense.id.uuidString
        #endif
    }

    func delete(id: UUID) async throws {
        guard provider.isConfigured else { throw AuthError.notConfigured }
        #if canImport(Supabase)
        guard let client = provider.client else { throw AuthError.notConfigured }
        try await client
            .from("expenses")
            .delete()
            .eq("id", value: id.uuidString)
            .execute()
        #endif
    }
}
