import Foundation

/// Pure ownership policy for expenses, kept out of the views and the sync
/// engine so every rule is unit-testable in isolation.
///
/// Ownership is *claim-once*: a legacy record created before ownership
/// partitioning (`ownerId == nil`) is claimed by the first signed-in user that
/// touches it, after which the id is immutable. A record owned by another
/// account is never shown, never uploaded, and never re-stamped.
enum ExpenseOwnership {

    /// Normalizes an owner id for comparison. Supabase `auth.uid()` is
    /// canonical lowercase uuid text; an empty/whitespace id is treated as
    /// "no owner" so a signed-out state can never match a real record.
    static func normalized(_ ownerId: String?) -> String? {
        guard let trimmed = ownerId?.trimmingCharacters(in: .whitespacesAndNewlines),
              !trimmed.isEmpty else { return nil }
        return trimmed.lowercased()
    }

    /// True when `recordOwnerId` may be read/written by `activeOwnerId`.
    /// Unowned (legacy) records are claimable, so they are visible.
    /// Signed out (`activeOwnerId == nil`) matches nothing.
    static func isAccessible(recordOwnerId: String?, activeOwnerId: String?) -> Bool {
        guard let active = normalized(activeOwnerId) else { return false }
        guard let owner = normalized(recordOwnerId) else { return true }
        return owner == active
    }

    /// The owner id to persist on a record before a push, or nil when the
    /// record must not be touched by this session. Already-owned records keep
    /// their original id — a push NEVER re-stamps another account's record.
    static func resolvedOwnerId(recordOwnerId: String?, activeOwnerId: String?) -> String? {
        guard let active = normalized(activeOwnerId) else { return nil }
        guard let owner = normalized(recordOwnerId) else { return active }
        return owner == active ? owner : nil
    }

    /// Expenses the signed-in user may see. Locally-deleted records are
    /// tombstones pending remote deletion and stay hidden.
    static func visibleExpenses(_ expenses: [Expense], activeOwnerId: String?) -> [Expense] {
        guard normalized(activeOwnerId) != nil else { return [] }
        return expenses.filter {
            $0.syncState != .deleted
                && isAccessible(recordOwnerId: $0.ownerId, activeOwnerId: activeOwnerId)
        }
    }

    /// How a local delete must be applied.
    enum DeletionPlan: Equatable {
        /// Never synced: safe to remove from the local store immediately.
        case removeLocally
        /// Exists (or may exist) remotely: keep a `.deleted` tombstone and
        /// remove it only after the remote delete succeeds.
        case tombstone
    }

    static func deletionPlan(for expense: Expense) -> DeletionPlan {
        switch expense.syncState {
        case .local:
            // Created locally and never pushed — nothing remote to clean up.
            return expense.remoteId == nil ? .removeLocally : .tombstone
        case .synced, .dirty, .deleted:
            return .tombstone
        }
    }
}
