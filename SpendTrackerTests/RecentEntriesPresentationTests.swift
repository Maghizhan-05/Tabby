import XCTest
@testable import SpendTracker

final class RecentEntriesPresentationTests: XCTestCase {
    func testCategoryAccentUsesFixedPaletteForDefaultCategories() {
        XCTAssertEqual(Theme.categoryAccent(for: "Food"), .food)
        XCTAssertEqual(Theme.categoryAccent(for: "Transport"), .transport)
        XCTAssertEqual(Theme.categoryAccent(for: "Groceries"), .groceries)
        XCTAssertEqual(Theme.categoryAccent(for: "Bills"), .bills)
        XCTAssertEqual(Theme.categoryAccent(for: "Shopping"), .shopping)
        XCTAssertEqual(Theme.categoryAccent(for: "Entertainment"), .entertainment)
        XCTAssertEqual(Theme.categoryAccent(for: "Health"), .health)
        XCTAssertEqual(Theme.categoryAccent(for: "Other"), .other)
    }

    func testCategoryAccentNormalizesNamesAndUsesNeutralFallback() {
        XCTAssertEqual(Theme.categoryAccent(for: "  food "), .food)
        XCTAssertEqual(Theme.categoryAccent(for: "Custom category"), .neutral)
    }

    @MainActor
    func testRecentEntryUsesNoteAsTitleAndCategoryAsSubtitle() {
        let expense = Expense(amount: 12, categoryName: "Food", note: "Team lunch")

        XCTAssertEqual(RecentEntryPresentation.title(for: expense), "Team lunch")
        XCTAssertEqual(RecentEntryPresentation.category(for: expense), "Food")
    }

    @MainActor
    func testRecentEntryUsesCategoryExpenseAsFallbackTitle() {
        let expense = Expense(amount: 12, categoryName: "Transport")

        XCTAssertEqual(RecentEntryPresentation.title(for: expense), "Transport expense")
    }
}
