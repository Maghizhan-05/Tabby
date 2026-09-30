import XCTest
@testable import SpendTracker

final class DonutSelectionTests: XCTestCase {
    func testMapsAngleValueToTheMatchingCumulativeSegment() {
        XCTAssertEqual(DonutSelection.index(for: 4, values: [3, 5, 2]), 1)
    }

    func testMapsBoundaryToTheEarlierSegment() {
        XCTAssertEqual(DonutSelection.index(for: 3, values: [3, 5, 2]), 0)
    }

    func testReturnsNilForEmptyOrOutOfRangeAngleValues() {
        XCTAssertNil(DonutSelection.index(for: 1, values: []))
        XCTAssertNil(DonutSelection.index(for: 11, values: [3, 5, 2]))
    }

    func testTogglesMatchingCategoryOffAndSelectsDifferentCategory() {
        XCTAssertNil(DonutSelection.category(after: .toggle("Food"), current: "Food"))
        XCTAssertEqual(DonutSelection.category(after: .toggle("Travel"), current: "Food"), "Travel")
        XCTAssertEqual(DonutSelection.category(after: .toggle("Food"), current: nil), "Food")
    }

    func testOnlyTheLatestDeferredIntentMayApply() {
        XCTAssertFalse(DonutSelection.isLatest(generation: 2, currentGeneration: 3))
        XCTAssertTrue(DonutSelection.isLatest(generation: 3, currentGeneration: 3))
        XCTAssertNil(DonutSelection.category(after: .clear, current: "Travel"))
    }
}
