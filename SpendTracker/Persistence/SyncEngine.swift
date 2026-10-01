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
    ///
    /// Seeded defaults (`isDefault`) are never pushed — they exist identically
    /// on every device and belong to no account. Custom categories are stamped
    /// with the signed-in owner and `.deleted` tombstones are finalized only
    /// after the remote delete succeeds (mirrors expenses and friends).
    func pushUnsyncedCategories(ownerId: String? = nil) async {
        guard let owner = ExpenseOwnership.normalized(ownerId) else { return }

        let descriptor = FetchDescriptor<Category>(
            predicate: #Predicate<Category> { $0.syncStateRaw != 1 }
        )
        guard let fetched = try? modelContext.fetch(descriptor) else { return }
        let categories = fetched.filter { category in
            guard !category.isDefault else { return false }
            return ExpenseOwnership.isAccessible(
                recordOwnerId: category.ownerId, activeOwnerId: owner
            )
        }
        guard !categories.isEmpty else { return }

        for category in categories {
            // Never re-stamp another account's record.
            guard let resolvedOwner = ExpenseOwnership.resolvedOwnerId(
                recordOwnerId: category.ownerId, activeOwnerId: owner
            ) else { continue }

            do {
                if category.syncState == .deleted {
                    try await categoryRepository.delete(id: category.id)
                    modelContext.delete(category)
                    continue
                }
                category.ownerId = resolvedOwner
                try await categoryRepository.upsert(category)
                guard category.syncState != SyncState.deleted else { continue }
                category.remoteId = category.id.uuidString
                category.syncState = .synced
            } catch {
                // Leave unsynced; will retry on next push.
                continue
            }
        }
        try? modelContext.save()
    }

    // MARK: - Bidirectional category sync

    /// Apply the remote custom-category snapshot to the local store.
    ///
    /// Same safeguards as expenses and friends: a failed or partial fetch
    /// returns false without applying anything, so a transport error is never
    /// mistaken for "everything was deleted".
    @discardableResult
    func pullCategories(ownerId: String?) async -> Bool {
        guard let owner = ExpenseOwnership.normalized(ownerId) else { return false }

        let remote: [RemoteCategoryRow]
        do {
            remote = try await categoryRepository.fetchAll(ownerId: owner)
        } catch {
            return false
        }

        guard let localCategories = try? modelContext.fetch(FetchDescriptor<Category>()) else {
            return false
        }

        let plan = CategoryReconciliation.plan(
            local: localCategories.map {
                CategoryReconciliation.LocalRecord(
                    id: $0.id,
                    ownerId: $0.ownerId,
                    name: $0.name,
                    sortOrder: $0.sortOrder,
                    isDefault: $0.isDefault,
                    syncState: $0.syncState,
                    hasRemoteIdentity: $0.remoteId != nil
                )
            },
            remote: remote,
            activeOwnerId: owner
        )
        guard !plan.isEmpty else { return false }

        let byID = Dictionary(
            localCategories.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first }
        )

        for row in plan.inserts {
            modelContext.insert(
                Category(
                    id: row.id,
                    name: row.name,
                    isDefault: false,
                    sortOrder: row.sortOrder,
                    syncState: .synced,
                    remoteId: row.id.uuidString,
                    ownerId: owner
                )
            )
        }

        for row in plan.updates {
            guard let local = byID[row.id] else { continue }
            local.name = row.name
            local.sortOrder = row.sortOrder
            local.remoteId = row.id.uuidString
            local.syncState = .synced
        }

        for id in plan.deletions {
            guard let local = byID[id], !local.isDefault else { continue }
            modelContext.delete(local)
        }

        try? modelContext.save()
        return true
    }

    /// Reconcile categories against a complete remote snapshot, then push.
    /// Pull-before-push — see `syncExpenses` for why the ordering matters.
    @discardableResult
    func syncCategories(ownerId: String?) async -> Bool {
        let changed = await pullCategories(ownerId: ownerId)
        await pushUnsyncedCategories(ownerId: ownerId)
        return changed
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
        // Normalized comparison: locally-created rows carry the session's
        // uppercase uuid string, pulled rows Postgres's lowercase form.
        let friends = fetched.filter {
            ExpenseOwnership.isAccessible(recordOwnerId: $0.ownerId, activeOwnerId: ownerId)
        }
        guard !friends.isEmpty else { return }

        for friend in friends {
            // Never re-stamp another account's record.
            guard let owner = ExpenseOwnership.resolvedOwnerId(
                recordOwnerId: friend.ownerId,
                activeOwnerId: ownerId
            ) else { continue }

            do {
                if friend.syncState == .deleted {
                    try await friendRepository.delete(id: friend.id)
                    modelContext.delete(friend)
                } else {
                    friend.ownerId = owner
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
        await pushUnsyncedCategories(ownerId: ownerId)
        await pushUnsyncedExpenses(ownerId: ownerId)
        await pushUnsyncedFriends(ownerId: ownerId)
    }

    // MARK: - Bidirectional friend sync

    /// Apply the remote friend snapshot to the local store.
    ///
    /// Same safety properties as `pullExpenses`: a failed or partial fetch
    /// returns false WITHOUT applying anything, so an error can never be read
    /// as "everything was deleted".
    @discardableResult
    func pullFriends(ownerId: String?) async -> Bool {
        guard let activeOwner = ExpenseOwnership.normalized(ownerId) else { return false }

        let remote: [RemoteFriendRow]
        do {
            remote = try await friendRepository.fetchAll(ownerId: activeOwner)
        } catch {
            return false
        }

        guard let localFriends = try? modelContext.fetch(FetchDescriptor<Friend>()) else {
            return false
        }

        let plan = FriendReconciliation.plan(
            local: localFriends.map {
                FriendReconciliation.LocalRecord(
                    id: $0.id,
                    ownerId: $0.ownerId,
                    updatedAt: $0.updatedAt,
                    syncState: $0.syncState,
                    // Friend has no remoteId column; `.dirty` is only ever set
                    // by editing a previously `.synced` row, so it is the
                    // reliable "the backend has seen this" signal.
                    hasRemoteIdentity: $0.syncState == .dirty
                )
            },
            remote: remote,
            activeOwnerId: activeOwner
        )
        guard !plan.isEmpty else { return false }

        var byID: [UUID: Friend] = [:]
        for friend in localFriends { byID[friend.id] = friend }

        for row in plan.inserts {
            modelContext.insert(
                Friend(
                    id: row.id,
                    name: row.name,
                    ownerId: activeOwner,
                    theyOweUs: row.theyOweUs,
                    weOweThem: row.weOweThem,
                    createdAt: row.createdAt,
                    updatedAt: row.updatedAt,
                    syncState: .synced
                )
            )
        }

        for update in plan.updates {
            guard let friend = byID[update.id] else { continue }
            friend.name = update.row.name
            friend.theyOweUs = update.row.theyOweUs
            friend.weOweThem = update.row.weOweThem
            friend.updatedAt = update.row.updatedAt
            friend.ownerId = activeOwner
            friend.syncState = .synced
        }

        for id in plan.deletions {
            guard let friend = byID[id] else { continue }
            modelContext.delete(friend)
        }

        try? modelContext.save()
        return true
    }

    /// Reconcile against a complete remote snapshot, then push local work.
    /// Pull-before-push — see `syncExpenses` for why the ordering matters.
    @discardableResult
    func syncFriends(ownerId: String?) async -> Bool {
        let changed = await pullFriends(ownerId: ownerId)
        await pushUnsyncedFriends(ownerId: ownerId)
        return changed
    }

    // MARK: - Bidirectional expense sync

    /// Reconcile against a complete remote snapshot, then push local work.
    ///
    /// Pull-before-push is load-bearing for the resurrection bug: if a stale
    /// local row that was deleted on another device were pushed first, the
    /// upload would re-create it remotely and the snapshot would then "confirm"
    /// it forever. Pulling first lets absence delete it; local tombstones and
    /// genuinely new/edited rows are still pushed immediately afterwards, so
    /// nothing local is lost.
    @discardableResult
    func syncExpenses(ownerId: String?) async -> Bool {
        let changed = await pullExpenses(ownerId: ownerId)
        await pushUnsyncedExpenses(ownerId: ownerId)
        return changed
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
                    syncState: $0.syncState,
                    hasRemoteIdentity: $0.remoteId != nil
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
