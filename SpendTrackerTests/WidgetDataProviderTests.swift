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

    func testDailySnapshotKeepsLegacyWrapperAndIncludesOnlyToday() throws {
        let (container, context) = try makeStore()
        let now = makeDate(day: 18)
        context.insert(Expense(amount: 10, categoryName: "Food", date: now))
        context.insert(Expense(amount: 20, categoryName: "Transport", date: dateAdding(days: -1, to: now)))
        try context.save()

        let legacy = WidgetDataProvider.snapshot(from: container, now: now)
        let snapshot = WidgetDataProvider.snapshot(from: container, mode: .daily, now: now)

        XCTAssertEqual(legacy.todayTotal, 10)
        XCTAssertEqual(snapshot.total, 10)
        XCTAssertEqual(snapshot.title, "Today")
        XCTAssertEqual(snapshot.slices.map(\.category), ["Food"])
    }

    func testWeeklyMonthlyAndYearlySnapshotsUseTheirCurrentCalendarIntervals() throws {
        let (container, context) = try makeStore()
        let now = makeDate(day: 18)
        context.insert(Expense(amount: 10, categoryName: "Today", date: now))
        context.insert(Expense(amount: 20, categoryName: "This Week", date: dateAdding(days: -1, to: now)))
        context.insert(Expense(amount: 30, categoryName: "This Month", date: makeDate(day: 2)))
        context.insert(Expense(amount: 40, categoryName: "This Year", date: makeDate(month: 1, day: 15)))
        context.insert(Expense(amount: 50, categoryName: "Last Year", date: makeDate(year: 2025, month: 12, day: 30)))
        try context.save()

        let weekly = WidgetDataProvider.snapshot(from: container, mode: .weekly, now: now)
        let monthly = WidgetDataProvider.snapshot(from: container, mode: .monthly, now: now)
        let yearly = WidgetDataProvider.snapshot(from: container, mode: .yearly, now: now)

        XCTAssertEqual(weekly.total, 30)
        XCTAssertEqual(monthly.total, 60)
        XCTAssertEqual(yearly.total, 100)
        XCTAssertEqual(weekly.title, "This Week")
        XCTAssertEqual(monthly.title, "This Month")
        XCTAssertEqual(yearly.title, "This Year")
    }

    func testCategoriesSnapshotAggregatesAllStoredExpenses() throws {
        let (container, context) = try makeStore()
        let now = makeDate(day: 18)
        context.insert(Expense(amount: 10, categoryName: "Food", date: now))
        context.insert(Expense(amount: 25, categoryName: "Food", date: makeDate(month: 1, day: 1)))
        context.insert(Expense(amount: 20, categoryName: "Transport", date: makeDate(year: 2025, month: 12, day: 30)))
        try context.save()

        let snapshot = WidgetDataProvider.snapshot(from: container, mode: .categories, now: now)

        XCTAssertEqual(snapshot.title, "By Category")
        XCTAssertEqual(snapshot.total, 55)
        XCTAssertEqual(snapshot.slices.map(\.category), ["Food", "Transport"])
        XCTAssertEqual(snapshot.slices.map(\.total), [35, 20])
    }

    func testTrendsSnapshotContainsSixWeeklyTotalsAndCurrentWeekDelta() throws {
        let (container, context) = try makeStore()
        let now = makeDate(day: 18)
        for (offset, amount) in zip([0, -7, -14, -21, -28, -35], [60, 50, 40, 30, 20, 10]) {
            context.insert(Expense(amount: Decimal(amount), categoryName: "Food", date: dateAdding(days: offset, to: now)))
        }
        try context.save()

        let snapshot = WidgetDataProvider.snapshot(from: container, mode: .trends, now: now)

        XCTAssertEqual(snapshot.title, "Weekly Trend")
        XCTAssertEqual(snapshot.trendTotals.map(\.total), [10, 20, 30, 40, 50, 60])
        XCTAssertEqual(snapshot.trendDelta, 10)
    }

    func testAnalyticsWidgetModeExposesAllSixConfigurationChoices() {
        XCTAssertEqual(
            AnalyticsWidgetMode.allCases,
            [.daily, .weekly, .monthly, .yearly, .categories, .trends]
        )
        XCTAssertEqual(AnalyticsModeIntent().mode, .daily)
    }

    func testOverflowCategoriesAggregateIntoOtherSoSlicesMatchTotal() throws {
        let (container, context) = try makeStore()
        let now = makeDate(day: 18)
        let amounts: [(String, Decimal)] = [
            ("A", 100), ("B", 90), ("C", 80), ("D", 70),
            ("E", 60), ("F", 50), ("G", 40), ("H", 30),
        ]
        for (name, amount) in amounts {
            context.insert(Expense(amount: amount, categoryName: name, date: now))
        }
        try context.save()

        let snapshot = WidgetDataProvider.snapshot(from: container, mode: .categories, now: now)

        XCTAssertEqual(snapshot.slices.count, 6)
        XCTAssertEqual(snapshot.slices.map(\.category), ["A", "B", "C", "D", "E", "Other"])
        // F + G + H are aggregated rather than dropped.
        XCTAssertEqual(snapshot.slices.last?.total, 120)
        XCTAssertEqual(snapshot.slices.reduce(0) { $0 + $1.total }, snapshot.total)
    }

    func testSixOrFewerCategoriesAreNotCollapsedIntoOther() throws {
        let (container, context) = try makeStore()
        let now = makeDate(day: 18)
        for (name, amount) in [("A", Decimal(60)), ("B", 50), ("C", 40), ("D", 30), ("E", 20), ("F", 10)] {
            context.insert(Expense(amount: amount, categoryName: name, date: now))
        }
        try context.save()

        let snapshot = WidgetDataProvider.snapshot(from: container, mode: .categories, now: now)

        XCTAssertEqual(snapshot.slices.map(\.category), ["A", "B", "C", "D", "E", "F"])
        XCTAssertEqual(snapshot.slices.reduce(0) { $0 + $1.total }, snapshot.total)
    }

    func testEmptyStoreProducesHonestZeroSnapshotForEveryMode() throws {
        let (container, _) = try makeStore()
        let now = makeDate(day: 18)

        for mode in AnalyticsWidgetMode.allCases {
            let snapshot = WidgetDataProvider.snapshot(from: container, mode: mode, now: now)
            XCTAssertEqual(snapshot.total, 0, "mode: \(mode)")
            XCTAssertTrue(snapshot.slices.isEmpty, "mode: \(mode)")
            XCTAssertEqual(snapshot.trendDelta, 0, "mode: \(mode)")
            XCTAssertFalse(snapshot.trendTotals.contains { $0.total > 0 }, "mode: \(mode)")
        }
    }

    func testUnchangedWeeklySpendProducesZeroTrendDeltaRatherThanAnIncrease() throws {
        let (container, context) = try makeStore()
        let now = makeDate(day: 18)
        context.insert(Expense(amount: 25, categoryName: "Food", date: now))
        context.insert(Expense(amount: 25, categoryName: "Food", date: dateAdding(days: -7, to: now)))
        try context.save()

        let snapshot = WidgetDataProvider.snapshot(from: container, mode: .trends, now: now)

        XCTAssertEqual(snapshot.trendDelta, 0)
    }

    private func makeStore() throws -> (ModelContainer, ModelContext) {
        let storeURL = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent("WidgetDataProviderTests-\(UUID().uuidString).store")
        addTeardownBlock { try? FileManager.default.removeItem(at: storeURL) }
        let schema = Schema([Expense.self, Category.self, UserProfile.self])
        let container = try ModelContainer(
            for: schema,
            configurations: [ModelConfiguration(schema: schema, url: storeURL)]
        )
        return (container, ModelContext(container))
    }

    private func makeDate(year: Int = 2026, month: Int = 3, day: Int) -> Date {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(secondsFromGMT: 0)!
        return calendar.date(from: DateComponents(year: year, month: month, day: day, hour: 12))!
    }

    private func dateAdding(days: Int, to date: Date) -> Date {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(secondsFromGMT: 0)!
        return calendar.date(byAdding: .day, value: days, to: date)!
    }
}
