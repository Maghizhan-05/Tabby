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
struct ExpenseUpsertPayload: Encodable {
    let id: String
    let user_id: String
    let amount: String        // numeric(12,2) — send as string to preserve precision
    let category_name: String
    let note: String?
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
        // Prefer the refreshed `session` (guaranteed valid). Fall back to the
        // stored `currentSession` so a transient refresh hiccup doesn't block a
        // write when we already hold a persisted session.
        // Lowercase to match Postgres's canonical uuid text form (auth.uid()).
        let userId: String
        do {
            userId = try await client.auth.session.user.id.uuidString.lowercased()
        } catch {
            guard let stored = client.auth.currentSession else { throw error }
            userId = stored.user.id.uuidString.lowercased()
        }

        // Never upload one account's record under another account's identity.
        // Unowned legacy rows are claimable; a mismatch is a hard failure.
        if let ownerId = ExpenseOwnership.normalized(expense.ownerId), ownerId != userId {
            throw AuthError.providerUnavailable("Expense owner mismatch")
        }

        let iso = ISO8601DateFormatter()
        let row = ExpenseUpsertPayload(
            id: expense.id.uuidString,
            user_id: userId,
            amount: NSDecimalNumber(decimal: expense.amount).stringValue,
            category_name: expense.categoryName,
            note: expense.note,
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
