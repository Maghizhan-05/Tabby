import XCTest
import SwiftData
@testable import SpendTracker

@MainActor
final class WidgetDataProviderTests: XCTestCase {
    func testCompactCurrencyUsesOneDecimalThousandsForLongWidgetTotals() {
        XCTAssertEqual(WidgetCurrencyFormatter.string(23_410), "₹23.4K")
        XCTAssertEqual(WidgetCurrencyFormatter.string(1_200_000), "₹1.2M")
    }

    func testSnapshotReflectsTodayExpenseWrittenToTheSameStore() throws {
        let storeURL = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent("WidgetDataProviderTests-\(UUID().uuidString).store")
        defer { try? FileManager.default.removeItem(at: storeURL) }

        let schema = Schema([Expense.self, Category.self, UserProfile.self])
        let appContainer = try ModelContainer(
            for: schema,
            configurations: [ModelConfiguration(schema: schema, url: storeURL)]
        )
        let widgetContainer = try ModelContainer(
            for: schema,
            configurations: [ModelConfiguration(schema: schema, url: storeURL)]
        )
        let now = Date()
        let appContext = ModelContext(appContainer)
        appContext.insert(Expense(amount: 500, categoryName: "Transport", date: now))
        try appContext.save()

        let snapshot = WidgetDataProvider.snapshot(from: widgetContainer, now: now)

        XCTAssertEqual(snapshot.todayTotal, 500)
        XCTAssertEqual(snapshot.slices.first?.category, "Transport")
    }
}
