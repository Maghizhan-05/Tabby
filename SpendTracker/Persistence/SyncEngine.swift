import Foundation
import SwiftData

/// Offline-first sync engine. All writes are local-first; this pushes
/// unsynced records to the backend and upserts idempotently by UUID.
@MainActor
final class SyncEngine {
    private let modelContext: ModelContext
    private let expenseRepository: ExpenseRepositoring
    private let categoryRepository: CategoryRepositoring

    init(
        modelContext: ModelContext,
        expenseRepository: ExpenseRepositoring,
        categoryRepository: CategoryRepositoring
    ) {
        self.modelContext = modelContext
        self.expenseRepository = expenseRepository
        self.categoryRepository = categoryRepository
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

    /// Push any locally-created custom categories to the backend.
    func pushUnsyncedCategories() async {
        let descriptor = FetchDescriptor<Category>(
            predicate: #Predicate<Category> { $0.isDefault == false }
        )
        guard let categories = try? modelContext.fetch(descriptor), !categories.isEmpty else { return }

        for category in categories {
            try? await categoryRepository.upsert(category)
        }
    }

    /// Convenience: push everything that needs syncing.
    func pushAll() async {
        await pushUnsyncedCategories()
        await pushUnsyncedExpenses()
    }
}
