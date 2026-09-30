import Foundation
import SwiftUI
import SwiftData
import WidgetKit

@MainActor
final class QuickEntryViewModel: ObservableObject {
    @Published var amountText = ""
    @Published var categoryQuery = ""
    @Published var selectedDate = Date()
    @Published var notice: String?

    var amount: Decimal? {
        let cleaned = amountText.replacingOccurrences(of: ",", with: ".")
        return Decimal(string: cleaned)
    }

    var canSubmit: Bool {
        guard let amount, amount > 0 else { return false }
        return !categoryQuery.trimmingCharacters(in: .whitespaces).isEmpty
    }

    /// Filters categories by the search query (case-insensitive, trimmed).
    func filtered(_ categories: [Category]) -> [Category] {
        let query = categoryQuery.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !query.isEmpty else { return categories }
        return categories.filter {
            $0.name.range(of: query, options: .caseInsensitive) != nil
        }
    }

    /// True when the typed query does not match any existing category exactly.
    func isNewCategory(_ categories: [Category]) -> Bool {
        let query = categoryQuery.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !query.isEmpty else { return false }
        return !categories.contains {
            $0.name.compare(query, options: .caseInsensitive) == .orderedSame
        }
    }

    /// Resolves (or creates) a category name, returning the canonical stored name.
    @discardableResult
    func resolveCategory(_ categories: [Category], context: ModelContext) -> String {
        let query = categoryQuery.trimmingCharacters(in: .whitespacesAndNewlines)
        if let existing = categories.first(where: {
            $0.name.compare(query, options: .caseInsensitive) == .orderedSame
        }) {
            return existing.name
        }
        let sortOrder = (categories.map(\.sortOrder).max() ?? 0) + 1
        let category = Category(name: query, isDefault: false, sortOrder: sortOrder)
        context.insert(category)
        return query
    }

    /// Saves the expense locally-first. Returns the saved Expense on success.
    @discardableResult
    func submit(categories: [Category], context: ModelContext) -> Expense? {
        guard let amount, amount > 0 else {
            notice = "Enter a valid amount."
            return nil
        }
        let categoryName = resolveCategory(categories, context: context)
        let expense = Expense(
            amount: amount,
            categoryName: categoryName,
            date: selectedDate
        )
        context.insert(expense)
        do {
            try context.save()
            // Push the new entry to the home-screen widget's shared App-Group
            // store view so today's totals/ring refresh promptly.
            WidgetCenter.shared.reloadAllTimelines()
            return expense
        } catch {
            notice = "Could not save. Try again."
            return nil
        }
    }
}
