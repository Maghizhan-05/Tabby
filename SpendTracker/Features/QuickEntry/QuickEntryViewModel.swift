import Foundation
import SwiftUI
import SwiftData
import WidgetKit

@MainActor
final class QuickEntryViewModel: ObservableObject {
    @Published var amountText = ""
    @Published var categoryQuery = ""
    @Published var noteText = "" {
        didSet {
            if noteText.count > Expense.maximumNoteLength {
                noteText = String(noteText.prefix(Expense.maximumNoteLength))
            }
        }
    }
    @Published var selectedDate = Date()
    @Published var notice: String?

    var amount: Decimal? {
        let cleaned = amountText.replacingOccurrences(of: ",", with: ".")
        return Decimal(string: cleaned)
    }

    var canSubmit: Bool {
        guard let amount, amount > 0 else { return false }
        return !categoryQuery.trimmingCharacters(in: .whitespaces).isEmpty && isNoteValid
    }

    /// Notes are optional but capped at 120 user-visible characters to keep
    /// quick entry compact and preserve a predictable sync payload.
    var isNoteValid: Bool { noteText.count <= Expense.maximumNoteLength }

    var normalizedNote: String? {
        Expense.normalizedNote(noteText)
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

    /// Saves the expense locally-first, stamped with the signed-in account's
    /// owner id so it can never be read or uploaded by another account.
    /// Returns the saved Expense on success.
    @discardableResult
    func submit(categories: [Category], context: ModelContext, ownerId: String?) -> Expense? {
        guard let amount, amount > 0 else {
            notice = "Enter a valid amount."
            return nil
        }
        guard let owner = ExpenseOwnership.normalized(ownerId) else {
            notice = "Sign in to save this spend."
            return nil
        }
        let categoryName = resolveCategory(categories, context: context)
        let expense = Expense(
            ownerId: owner,
            amount: amount,
            categoryName: categoryName,
            note: normalizedNote,
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
