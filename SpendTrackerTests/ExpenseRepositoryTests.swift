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
