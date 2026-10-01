import Foundation
import SwiftUI
import SwiftData

@MainActor
final class ProfileViewModel: ObservableObject {
    @Published var newCategoryName = ""
    @Published var notice: String?

    /// Adds a category with trimming and case-insensitive de-duplication.
    ///
    /// The new category is stamped with the signed-in owner so it can sync to
    /// the user's other devices; de-duplication only considers categories the
    /// current user can actually see.
    func addCategory(context: ModelContext, existing: [Category], ownerId: String? = nil) {
        let trimmed = newCategoryName.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }

        let visible = Self.visibleCategories(existing, ownerId: ownerId)
        let isDuplicate = visible.contains {
            $0.name.compare(trimmed, options: .caseInsensitive) == .orderedSame
        }
        guard !isDuplicate else {
            notice = "\"\(trimmed)\" already exists."
            return
        }

        let sortOrder = (visible.map(\.sortOrder).max() ?? 0) + 1
        let category = Category(
            name: trimmed,
            isDefault: false,
            sortOrder: sortOrder,
            ownerId: ExpenseOwnership.normalized(ownerId)
        )
        context.insert(category)
        try? context.save()
        newCategoryName = ""
        notice = nil
    }

    /// Deletes a custom category.
    ///
    /// A category the backend has already seen becomes a `.deleted` tombstone so
    /// the next push propagates the deletion to the user's other devices; a
    /// never-uploaded category is removed outright. Seeded defaults are
    /// untouchable. Mirrors the expense/friend deletion contract.
    func deleteCategory(_ category: Category, context: ModelContext, ownerId: String? = nil) {
        guard !category.isDefault else { return }

        if let owner = ExpenseOwnership.normalized(ownerId),
           !ExpenseOwnership.isAccessible(
               recordOwnerId: category.ownerId, activeOwnerId: owner
           ) {
            // Never delete another account's record.
            return
        }

        if category.remoteId != nil || category.syncState == .synced {
            category.syncState = .deleted
        } else {
            context.delete(category)
        }
        try? context.save()
    }

    /// Categories the given owner may see: the seeded defaults plus their own
    /// custom ones, excluding anything pending deletion.
    static func visibleCategories(_ categories: [Category], ownerId: String?) -> [Category] {
        let owner = ExpenseOwnership.normalized(ownerId)
        return categories.filter { category in
            guard category.syncState != .deleted else { return false }
            if category.isDefault { return true }
            guard let owner else { return category.ownerId == nil }
            return ExpenseOwnership.isAccessible(
                recordOwnerId: category.ownerId, activeOwnerId: owner
            )
        }
    }
}
