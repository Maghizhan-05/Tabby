import XCTest
import SwiftData
@testable import SpendTracker

final class CategoryParsingTests: XCTestCase {

    @MainActor
    private func makeContext() throws -> ModelContext {
        let schema = Schema([Expense.self, Category.self, UserProfile.self])
        let config = ModelConfiguration(schema: schema, isStoredInMemoryOnly: true)
        let container = try ModelContainer(for: schema, configurations: [config])
        return ModelContext(container)
    }

    @MainActor
    func testTrimmingWhitespace() throws {
        let context = try makeContext()
        let vm = QuickEntryViewModel()
        vm.categoryQuery = "  Coffee  "
        let name = vm.resolveCategory([], context: context)
        XCTAssertEqual(name, "Coffee")
    }

    @MainActor
    func testCaseInsensitiveDeDuplication() throws {
        let context = try makeContext()
        let existing = Category(name: "Food", isDefault: true)
        context.insert(existing)

        let vm = QuickEntryViewModel()
        vm.categoryQuery = "food"
        // Should resolve to the existing canonical "Food", not create a duplicate.
        let name = vm.resolveCategory([existing], context: context)
        XCTAssertEqual(name, "Food")
        XCTAssertFalse(vm.isNewCategory([existing]))
    }

    @MainActor
    func testNewCategoryDetection() throws {
        let existing = Category(name: "Food", isDefault: true)
        let vm = QuickEntryViewModel()
        vm.categoryQuery = "Travel"
        XCTAssertTrue(vm.isNewCategory([existing]))
    }
}
