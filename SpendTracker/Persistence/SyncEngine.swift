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

    /// Push all locally-created or dirty expenses to the backend.
    /// Idempotent: uses upsert-by-UUID so re-running is safe.
    func pushUnsyncedExpenses() async {
        let predicate = #Predicate<Expense> { $0.syncStateRaw != 1 }
        let descriptor = FetchDescriptor<Expense>(predicate: predicate)
        guard let unsynced = try? modelContext.fetch(descriptor), !unsynced.isEmpty else { return }

        for expense in unsynced {
            do {
                let remoteId = try await expenseRepository.upsert(expense)
                expense.remoteId = remoteId
                expense.syncState = .synced
                expense.updatedAt = Date()
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

    /// Convenience: push everything that needs syncing.
    func pushAll(ownerId: String? = nil) async {
        await pushUnsyncedCategories()
        await pushUnsyncedExpenses()
        await pushUnsyncedFriends(ownerId: ownerId)
    }
}
