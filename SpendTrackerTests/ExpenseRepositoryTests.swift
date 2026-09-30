import XCTest
import SwiftData
@testable import SpendTracker

final class ExpenseRepositoryTests: XCTestCase {

    @MainActor
    func testExpenseInsertAndFetch() throws {
        let schema = Schema([Expense.self, Category.self, UserProfile.self])
        let config = ModelConfiguration(schema: schema, isStoredInMemoryOnly: true)
        let container = try ModelContainer(for: schema, configurations: [config])
        let context = ModelContext(container)

        let expense = Expense(amount: Decimal(12.50), categoryName: "Food")
        context.insert(expense)
        try context.save()

        let fetched = try context.fetch(FetchDescriptor<Expense>())
        XCTAssertEqual(fetched.count, 1)
        XCTAssertEqual(fetched.first?.categoryName, "Food")
        XCTAssertEqual(fetched.first?.syncState, .local)
    }

    @MainActor
    func testExpenseNoteIsNormalizedAndPersists() throws {
        let schema = Schema([Expense.self, Category.self, UserProfile.self])
        let config = ModelConfiguration(schema: schema, isStoredInMemoryOnly: true)
        let container = try ModelContainer(for: schema, configurations: [config])
        let context = ModelContext(container)

        context.insert(Expense(amount: 12.50, categoryName: "Food", note: "  Team lunch\n"))
        context.insert(Expense(amount: 5, categoryName: "Other", note: " \n\t "))
        try context.save()

        let fetched = try context.fetch(FetchDescriptor<Expense>(sortBy: [SortDescriptor(\.amount)]))
        XCTAssertEqual(fetched[0].note, nil)
        XCTAssertEqual(fetched[1].note, "Team lunch")
    }

    @MainActor
    func testQuickEntryNoteIsCappedAtMaximumLength() {
        let viewModel = QuickEntryViewModel()
        viewModel.noteText = String(repeating: "a", count: Expense.maximumNoteLength + 10)

        XCTAssertEqual(viewModel.noteText.count, Expense.maximumNoteLength)
        XCTAssertEqual(viewModel.normalizedNote, String(repeating: "a", count: Expense.maximumNoteLength))
    }

    func testExpensePayloadEncodesPresentNoteAndOmitsNilNote() throws {
        let withNote = ExpenseUpsertPayload(
            id: "expense-id",
            user_id: "user-id",
            amount: "12.5",
            category_name: "Food",
            note: "Team lunch",
            date: "2026-09-30T10:00:00Z",
            created_at: "2026-09-30T10:00:00Z",
            updated_at: "2026-09-30T10:00:00Z"
        )
        let withoutNote = ExpenseUpsertPayload(
            id: "expense-id",
            user_id: "user-id",
            amount: "12.5",
            category_name: "Food",
            note: nil,
            date: "2026-09-30T10:00:00Z",
            created_at: "2026-09-30T10:00:00Z",
            updated_at: "2026-09-30T10:00:00Z"
        )

        let withObject = try XCTUnwrap(JSONSerialization.jsonObject(with: JSONEncoder().encode(withNote)) as? [String: Any])
        let withoutObject = try XCTUnwrap(JSONSerialization.jsonObject(with: JSONEncoder().encode(withoutNote)) as? [String: Any])

        XCTAssertEqual(withObject["note"] as? String, "Team lunch")
        XCTAssertNil(withoutObject["note"])
    }

    @MainActor
    func testExpensePersistsOptionalNote() throws {
        let schema = Schema([Expense.self, Category.self, UserProfile.self])
        let config = ModelConfiguration(schema: schema, isStoredInMemoryOnly: true)
        let container = try ModelContainer(for: schema, configurations: [config])
        let context = ModelContext(container)

        context.insert(Expense(amount: 25, categoryName: "Food", note: "Team lunch"))
        try context.save()

        let fetched = try context.fetch(FetchDescriptor<Expense>())
        XCTAssertEqual(fetched.first?.note, "Team lunch")
    }

    @MainActor
    func testAnalyticsTotalsByCategory() {
        let a = Expense(amount: 10, categoryName: "Food")
        let b = Expense(amount: 5, categoryName: "Food")
        let c = Expense(amount: 20, categoryName: "Transport")
        let totals = SpendingAnalytics.totalsByCategory([a, b, c])
        // Transport (20) should rank first, Food (15) second.
        XCTAssertEqual(totals.first?.category, "Transport")
        XCTAssertEqual(totals.first?.total, 20)
        XCTAssertEqual(totals.count, 2)
    }
}
