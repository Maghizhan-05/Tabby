import Foundation
import SwiftData

/// Offline-first sync engine. All writes are local-first; this pushes
/// unsynced records to the backend and upserts idempotently by UUID.
@MainActor
final class SyncEngine {
    private let modelContext: ModelContext
    private let expenseRepository: ExpenseRepositoring
    private let categoryRepository: CategoryRepositoring
    private let friendRepository: FriendRepositoring
    /// Tail of the serialized expense-push chain. Overlapping pushes queue
    /// behind each other instead of interleaving upserts for the same record.
    private var pushGate: Task<Void, Never>?

    init(
        modelContext: ModelContext,
        expenseRepository: ExpenseRepositoring,
        categoryRepository: CategoryRepositoring,
        friendRepository: FriendRepositoring = SupabaseFriendRepository()
    ) {
        self.modelContext = modelContext
        self.expenseRepository = expenseRepository
        self.categoryRepository = categoryRepository
        self.friendRepository = friendRepository
    }

    /// Push all locally-created or dirty expenses belonging to the signed-in
    /// user to the backend. Idempotent: uses upsert-by-UUID so re-running is
    /// safe. Records owned by another account are never uploaded under this
    /// session's credentials, and locally-deleted records are removed from the
    /// local store only after their remote delete succeeds.
    ///
    /// Concurrency: pushes are serialized per engine-owned task via
    /// `pushGate`, and a record is marked `.synced` only when the revision that
    /// was uploaded is still the current one — an edit made while an upload is
    /// in flight stays dirty and is retried instead of being silently lost.
    func pushUnsyncedExpenses(ownerId: String?) async {
        guard let activeOwner = ExpenseOwnership.normalized(ownerId) else { return }

        // Serialize overlapping pushes: a second caller waits for the first.
        let previous = pushGate
        let task = Task { @MainActor in
            await previous?.value
            await self.performExpensePush(activeOwner: activeOwner)
        }
        pushGate = task
        await task.value
        if pushGate == task { pushGate = nil }
    }

    private func performExpensePush(activeOwner: String) async {
        let predicate = #Predicate<Expense> { $0.syncStateRaw != 1 }
        let descriptor = FetchDescriptor<Expense>(predicate: predicate)
        guard let fetched = try? modelContext.fetch(descriptor), !fetched.isEmpty else { return }

        // Only this account's records (plus unclaimed legacy rows) are pushed.
        let pushable = fetched.filter {
            ExpenseOwnership.isAccessible(recordOwnerId: $0.ownerId, activeOwnerId: activeOwner)
        }
        guard !pushable.isEmpty else { return }

        for expense in pushable {
            // Re-check ownership at push time; never re-stamp another account.
            guard let owner = ExpenseOwnership.resolvedOwnerId(
                recordOwnerId: expense.ownerId,
                activeOwnerId: activeOwner
            ) else { continue }

            do {
                if expense.syncState == .deleted {
                    // Tombstone: the local row disappears only once the remote
                    // row is gone, so a deletion can never be lost offline.
                    try await expenseRepository.delete(id: expense.id)
                    modelContext.delete(expense)
                } else {
                    expense.ownerId = owner
                    let uploadedRevision = expense.revision
                    let remoteId = try await expenseRepository.upsert(expense)
                    // Guard against a concurrent edit (or delete) landing while
                    // the upload was suspended.
                    guard expense.revision == uploadedRevision,
                          expense.syncState != .deleted else { continue }
                    expense.remoteId = remoteId
                    expense.syncState = .synced
                    expense.updatedAt = Date()
                }
            } catch {
                // Leave unsynced; will retry on next push.
                continue
            }
        }
        try? modelContext.save()
    }

    /// Push any locally-created / dirty custom categories to the backend.
    /// Only categories whose sync state is not `.synced` are pushed, and each is
    /// marked `.synced` only after a successful backend write (mirrors expenses).
    func pushUnsyncedCategories() async {
        let descriptor = FetchDescriptor<Category>(
            predicate: #Predicate<Category> { $0.syncStateRaw != 1 }
        )
        guard let categories = try? modelContext.fetch(descriptor), !categories.isEmpty else { return }

        for category in categories {
            do {
                try await categoryRepository.upsert(category)
                category.remoteId = category.id.uuidString
                category.syncState = .synced
            } catch {
                // Leave unsynced; will retry on next push.
                continue
            }
        }
        try? modelContext.save()
    }

    /// Push local/dirty friends and finalize locally-deleted friends only after
    /// their authenticated remote delete succeeds. UUIDs are canonical on both sides.
    /// Pushes only the signed-in user's friends. Records belonging to another
    /// account are never uploaded under the current session's credentials.
    func pushUnsyncedFriends(ownerId: String?) async {
        guard let ownerId, !ownerId.isEmpty else { return }
        let descriptor = FetchDescriptor<Friend>(
            predicate: #Predicate<Friend> { $0.syncStateRaw != 1 }
        )
        guard let fetched = try? modelContext.fetch(descriptor) else { return }
        let friends = fetched.filter { $0.ownerId == ownerId }
        guard !friends.isEmpty else { return }

        for friend in friends {
            do {
                if friend.syncState == .deleted {
                    try await friendRepository.delete(id: friend.id)
                    modelContext.delete(friend)
                } else {
                    try await friendRepository.upsert(friend)
                    friend.syncState = .synced
                    friend.updatedAt = Date()
                }
            } catch {
                // Leave the record pending; the next sync retries it.
                continue
            }
        }
        try? modelContext.save()
    }

    /// Convenience: push everything that needs syncing for the signed-in user.
    func pushAll(ownerId: String? = nil) async {
        await pushUnsyncedCategories()
        await pushUnsyncedExpenses(ownerId: ownerId)
        await pushUnsyncedFriends(ownerId: ownerId)
    }

    // MARK: - Bidirectional expense sync

    /// Push local work, then reconcile against a complete remote snapshot.
    ///
    /// The ordering is load-bearing: absence from the snapshot is read as
    /// "deleted on another device", which is only sound once everything local
    /// has been uploaded. Never call `pullExpenses` without pushing first.
    @discardableResult
    func syncExpenses(ownerId: String?) async -> Bool {
        await pushUnsyncedExpenses(ownerId: ownerId)
        return await pullExpenses(ownerId: ownerId)
    }

    /// Apply the remote snapshot to the local store.
    ///
    /// Returns true when the local store changed (so the caller can reload
    /// widget timelines). A failed/partial fetch returns false WITHOUT applying
    /// anything — a truncated snapshot must never be mistaken for deletions.
    @discardableResult
    func pullExpenses(ownerId: String?) async -> Bool {
        guard let activeOwner = ExpenseOwnership.normalized(ownerId) else { return false }

        let remote: [RemoteExpenseRow]
        do {
            remote = try await expenseRepository.fetchAll(ownerId: activeOwner)
        } catch {
            // Abort before any deletion: an error is not an empty account.
            return false
        }

        guard let localExpenses = try? modelContext.fetch(FetchDescriptor<Expense>()) else {
            return false
        }

        let plan = ExpenseReconciliation.plan(
            local: localExpenses.map {
                ExpenseReconciliation.LocalRecord(
                    id: $0.id,
                    ownerId: $0.ownerId,
                    updatedAt: $0.updatedAt,
                    syncState: $0.syncState
                )
            },
            remote: remote,
            activeOwnerId: activeOwner
        )
        guard !plan.isEmpty else { return false }

        var byID: [UUID: Expense] = [:]
        for expense in localExpenses { byID[expense.id] = expense }

        for row in plan.inserts {
            let expense = Expense(
                id: row.id,
                ownerId: activeOwner,
                amount: row.amount,
                categoryName: row.categoryName,
                note: row.note,
                date: row.date,
                createdAt: row.createdAt,
                updatedAt: row.updatedAt,
                syncState: .synced,
                remoteId: row.id.uuidString
            )
            modelContext.insert(expense)
        }

        for update in plan.updates {
            guard let expense = byID[update.id] else { continue }
            expense.amount = update.row.amount
            expense.categoryName = update.row.categoryName
            expense.note = Expense.normalizedNote(update.row.note)
            expense.date = update.row.date
            expense.updatedAt = update.row.updatedAt
            expense.ownerId = activeOwner
            expense.remoteId = update.row.id.uuidString
            expense.syncState = .synced
        }

        for id in plan.deletions {
            guard let expense = byID[id] else { continue }
            modelContext.delete(expense)
        }

        try? modelContext.save()
        return true
    }
}
