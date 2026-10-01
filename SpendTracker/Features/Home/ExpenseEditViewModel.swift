import Foundation
import SwiftData
import WidgetKit

@MainActor
final class ExpenseEditViewModel: ObservableObject {
    @Published var amountText: String
    @Published var categoryQuery: String
    @Published var noteText: String {
        didSet {
            if noteText.count > Expense.maximumNoteLength {
                noteText = String(noteText.prefix(Expense.maximumNoteLength))
            }
        }
    }
    @Published var selectedDate: Date
    @Published var notice: String?

    private let expense: Expense
    private let now: () -> Date
    private let reloadWidgetTimelines: () -> Void
    private let pushExpenses: (ModelContext) async -> Void

    init(
        expense: Expense,
        now: @escaping () -> Date = { Date() },
        reloadWidgetTimelines: @escaping () -> Void = {
            WidgetCenter.shared.reloadAllTimelines()
        },
        pushExpenses: @escaping (ModelContext) async -> Void = { context in
            await SyncEngine(
                modelContext: context,
                expenseRepository: SupabaseExpenseRepository(),
                categoryRepository: SupabaseCategoryRepository()
            ).pushUnsyncedExpenses()
        }
    ) {
        self.expense = expense
        self.now = now
        self.reloadWidgetTimelines = reloadWidgetTimelines
        self.pushExpenses = pushExpenses
        amountText = NSDecimalNumber(decimal: expense.amount).stringValue
        categoryQuery = expense.categoryName
        noteText = expense.note ?? ""
        selectedDate = expense.date
    }

    var amount: Decimal? {
        Decimal(string: amountText.replacingOccurrences(of: ",", with: "."))
    }

    var canSave: Bool {
        guard let amount, amount > 0 else { return false }
        return !categoryQuery.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    @discardableResult
    func save(categories: [Category], context: ModelContext) -> Bool {
        guard let amount, amount > 0 else {
            notice = "Enter a valid amount."
            return false
        }

        let trimmedCategory = categoryQuery.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedCategory.isEmpty else {
            notice = "Choose a category."
            return false
        }

        let categoryName: String
        if let existing = categories.first(where: {
            $0.name.compare(trimmedCategory, options: .caseInsensitive) == .orderedSame
        }) {
            categoryName = existing.name
        } else {
            let sortOrder = (categories.map(\.sortOrder).max() ?? 0) + 1
            context.insert(Category(name: trimmedCategory, isDefault: false, sortOrder: sortOrder))
            categoryName = trimmedCategory
        }

        expense.amount = amount
        expense.categoryName = categoryName
        expense.note = Expense.normalizedNote(noteText)
        expense.date = selectedDate
        expense.updatedAt = now()
        if expense.syncState == .synced {
            expense.syncState = .dirty
        }

        do {
            try context.save()
            reloadWidgetTimelines()
            // Push the now-dirty edit. A failure leaves it dirty for the next
            // sync attempt rather than silently stranding the change locally.
            Task { await pushExpenses(context) }
            notice = nil
            return true
        } catch {
            context.rollback()
            notice = "Could not save. Try again."
            return false
        }
    }
}
