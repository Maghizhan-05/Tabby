import Foundation
import SwiftUI
import SwiftData

@MainActor
final class ProfileViewModel: ObservableObject {
    @Published var newCategoryName = ""
    @Published var notice: String?

    /// Adds a category with trimming and case-insensitive de-duplication.
    func addCategory(context: ModelContext, existing: [Category]) {
        let trimmed = newCategoryName.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }

        let isDuplicate = existing.contains {
            $0.name.compare(trimmed, options: .caseInsensitive) == .orderedSame
        }
        guard !isDuplicate else {
            notice = "\"\(trimmed)\" already exists."
            return
        }

        let sortOrder = (existing.map(\.sortOrder).max() ?? 0) + 1
        let category = Category(name: trimmed, isDefault: false, sortOrder: sortOrder)
        context.insert(category)
        try? context.save()
        newCategoryName = ""
        notice = nil
    }

    func deleteCategory(_ category: Category, context: ModelContext) {
        guard !category.isDefault else { return }
        context.delete(category)
        try? context.save()
    }
}
