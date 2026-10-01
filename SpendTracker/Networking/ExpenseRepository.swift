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
    /// Every remote row for `ownerId`, as a COMPLETE snapshot.
    ///
    /// Contract (absence-based deletion depends on it): the implementation must
    /// verify `ownerId` against the authenticated session and page until a
    /// short/empty page proves completeness. It MUST throw rather than return a
    /// partial list — a truncated snapshot would look like mass deletion to
    /// `ExpenseReconciliation`.
    func fetchAll(ownerId: String) async throws -> [RemoteExpenseRow]
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

    /// Rows requested per page. Supabase caps an unpaged `select` (1000 rows by
    /// default), so the snapshot is assembled from explicit ordered ranges.
    static let pageSize = 500

    /// The authenticated session's user id, lowercased to match Postgres's
    /// canonical uuid text form (`auth.uid()`).
    ///
    /// Prefer the refreshed `session` (guaranteed valid). Fall back to the
    /// stored `currentSession` so a transient refresh hiccup doesn't block a
    /// write when we already hold a persisted session.
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

    @discardableResult
    func upsert(_ expense: Expense) async throws -> String {
        guard provider.isConfigured else { throw AuthError.notConfigured }
        #if canImport(Supabase)
        guard let client = provider.client else { throw AuthError.notConfigured }

        // user_id must come from the authenticated session (RLS is auth.uid()-scoped).
        let userId = try await sessionUserId()

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

    /// A complete, owner-verified snapshot of the account's remote expenses.
    ///
    /// Safety properties that absence-based deletion relies on:
    /// 1. `ownerId` is checked against the id derived from the authenticated
    ///    session; a mismatch throws instead of returning an empty list (an
    ///    empty list for the wrong owner would erase that owner's local rows).
    /// 2. Rows are paged in a deterministic `id` order via `.range(...)` until a
    ///    short page proves the end was reached, so the 1000-row default cap can
    ///    never silently truncate the snapshot.
    /// 3. Any transport/decode failure propagates — the caller aborts the pull
    ///    before applying deletions.
    func fetchAll(ownerId: String) async throws -> [RemoteExpenseRow] {
        guard provider.isConfigured else { throw AuthError.notConfigured }
        guard let requested = ExpenseOwnership.normalized(ownerId) else {
            throw AuthError.providerUnavailable("Expense fetch requires an owner")
        }
        #if canImport(Supabase)
        guard let client = provider.client else { throw AuthError.notConfigured }

        // Trust the session, not the caller's argument.
        let sessionUser = try await sessionUserId()
        guard requested == sessionUser else {
            throw AuthError.providerUnavailable("Expense owner mismatch")
        }

        let decoder = JSONDecoder()
        return try await PaginatedSnapshot.fetchAll(pageSize: Self.pageSize) { from, to in
            let response = try await client
                .from("expenses")
                .select()
                .eq("user_id", value: sessionUser)
                .order("id", ascending: true)
                .range(from: from, to: to)
                .execute()
            return try decoder.decode([RemoteExpenseRow].self, from: response.data)
        }
        #else
        _ = requested
        return []
        #endif
    }
}
