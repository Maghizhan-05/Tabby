import XCTest
import SwiftData
@testable import SpendTracker

final class TransactionEditingTests: XCTestCase {
    /// Owner of every fixture expense in this suite.
    private static let owner = "owner-a"

    private final class RecordingExpenseRepository: ExpenseRepositoring {
        private(set) var upsertedIDs: [UUID] = []

        func upsert(_ expense: Expense) async throws -> String {
            upsertedIDs.append(expense.id)
            return expense.id.uuidString
        }

        func delete(id: UUID) async throws {}
    }

    private struct NoOpCategoryRepository: CategoryRepositoring {
        func upsert(_ category: SpendTracker.Category) async throws {}
        func delete(id: UUID) async throws {}
    }

    @MainActor
    private func makeContext() throws -> ModelContext {
        let schema = Schema([Expense.self, Category.self, UserProfile.self])
        let configuration = ModelConfiguration(schema: schema, isStoredInMemoryOnly: true)
        return ModelContext(try ModelContainer(for: schema, configurations: [configuration]))
    }

    @MainActor
    func testEditPrefillsAmountCategoryDateAndNote() {
        let date = Date(timeIntervalSince1970: 1_800_000_000)
        let expense = Expense(
            ownerId: Self.owner,
            amount: Decimal(string: "42.75")!,
            categoryName: "Food",
            note: "Team lunch",
            date: date
        )

        let viewModel = ExpenseEditViewModel(expense: expense)

        XCTAssertEqual(viewModel.amountText, "42.75")
        XCTAssertEqual(viewModel.categoryQuery, "Food")
        XCTAssertEqual(viewModel.selectedDate, date)
        XCTAssertEqual(viewModel.noteText, "Team lunch")
    }

    @MainActor
    func testSaveMutatesExistingExpenseWithoutCreatingDuplicate() throws {
        let context = try makeContext()
        let originalID = UUID()
        let originalCreatedAt = Date(timeIntervalSince1970: 1_700_000_000)
        let expense = Expense(
            id: originalID,
            ownerId: Self.owner,
            amount: 10,
            categoryName: "Food",
            date: originalCreatedAt,
            createdAt: originalCreatedAt
        )
        context.insert(expense)
        try context.save()

        let viewModel = ExpenseEditViewModel(expense: expense)
        viewModel.amountText = "25.50"
        viewModel.categoryQuery = "Transport"
        viewModel.noteText = "Airport ride"
        viewModel.selectedDate = Date(timeIntervalSince1970: 1_800_000_000)

        XCTAssertTrue(viewModel.save(categories: [], context: context, ownerId: Self.owner))

        let fetched = try context.fetch(FetchDescriptor<Expense>())
        XCTAssertEqual(fetched.count, 1)
        XCTAssertEqual(fetched.first?.id, originalID)
        XCTAssertEqual(fetched.first?.createdAt, originalCreatedAt)
        XCTAssertEqual(fetched.first?.amount, Decimal(string: "25.50"))
        XCTAssertEqual(fetched.first?.categoryName, "Transport")
        XCTAssertEqual(fetched.first?.note, "Airport ride")
        XCTAssertEqual(fetched.first?.date, Date(timeIntervalSince1970: 1_800_000_000))
    }

    @MainActor
    func testSaveUpdatesTimestampMarksSyncedExpenseDirtyAndReloadsWidgets() throws {
        let context = try makeContext()
        let originalUpdatedAt = Date(timeIntervalSince1970: 1_700_000_000)
        let editedAt = Date(timeIntervalSince1970: 1_800_000_100)
        let expense = Expense(
            ownerId: Self.owner,
            amount: 10,
            categoryName: "Food",
            updatedAt: originalUpdatedAt,
            syncState: .synced,
            remoteId: "remote-expense"
        )
        context.insert(expense)
        try context.save()
        var reloadCount = 0

        let viewModel = ExpenseEditViewModel(
            expense: expense,
            now: { editedAt },
            reloadWidgetTimelines: { reloadCount += 1 }
        )
        viewModel.amountText = "11"

        XCTAssertTrue(viewModel.save(categories: [], context: context, ownerId: Self.owner))
        XCTAssertEqual(expense.updatedAt, editedAt)
        XCTAssertGreaterThan(expense.updatedAt, originalUpdatedAt)
        XCTAssertEqual(expense.syncState, .dirty)
        XCTAssertEqual(expense.remoteId, "remote-expense")
        XCTAssertEqual(reloadCount, 1)
    }

    @MainActor
    func testInvalidEditDoesNotMutateExpenseOrReloadWidgets() throws {
        let context = try makeContext()
        let expense = Expense(ownerId: Self.owner, amount: 10, categoryName: "Food", note: "Original")
        context.insert(expense)
        try context.save()
        var reloadCount = 0
        let viewModel = ExpenseEditViewModel(
            expense: expense,
            reloadWidgetTimelines: { reloadCount += 1 }
        )

        viewModel.amountText = "0"
        XCTAssertFalse(viewModel.canSave)
        XCTAssertFalse(viewModel.save(categories: [], context: context, ownerId: Self.owner))
        XCTAssertEqual(viewModel.notice, "Enter a valid amount.")

        viewModel.amountText = "12"
        viewModel.categoryQuery = "   "
        XCTAssertFalse(viewModel.canSave)
        XCTAssertFalse(viewModel.save(categories: [], context: context, ownerId: Self.owner))
        XCTAssertEqual(viewModel.notice, "Choose a category.")

        XCTAssertEqual(expense.amount, 10)
        XCTAssertEqual(expense.categoryName, "Food")
        XCTAssertEqual(expense.note, "Original")
        XCTAssertEqual(reloadCount, 0)
    }

    @MainActor
    func testEditNoteIsCappedAtMaximumLength() {
        let expense = Expense(ownerId: Self.owner, amount: 10, categoryName: "Food")
        let viewModel = ExpenseEditViewModel(expense: expense)

        viewModel.noteText = String(repeating: "n", count: Expense.maximumNoteLength + 10)

        XCTAssertEqual(viewModel.noteText.count, Expense.maximumNoteLength)
    }

    @MainActor
    func testDirtyEditedExpenseSyncUpsertsSameUUID() async throws {
        let context = try makeContext()
        let expense = Expense(
            id: UUID(),
            ownerId: Self.owner,
            amount: 10,
            categoryName: "Food",
            syncState: .dirty,
            remoteId: "existing-remote-id"
        )
        context.insert(expense)
        try context.save()
        let repository = RecordingExpenseRepository()
        let engine = SyncEngine(
            modelContext: context,
            expenseRepository: repository,
            categoryRepository: NoOpCategoryRepository()
        )

        await engine.pushUnsyncedExpenses(ownerId: Self.owner)

        XCTAssertEqual(repository.upsertedIDs, [expense.id])
        XCTAssertEqual(expense.remoteId, expense.id.uuidString)
        XCTAssertEqual(expense.syncState, .synced)
    }
}
