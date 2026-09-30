import XCTest
@testable import SpendTracker

final class WidgetCurrencyFormatterTests: XCTestCase {
    func testCompactRupeeFormattingAcrossBoundaries() {
        let cases: [(Double, String)] = [
            (0, "₹0"),
            (500, "₹500"),
            (9_999, "₹9,999"),
            (10_000, "₹10K"),
            (23_410, "₹23.4K"),
            (999_949, "₹999.9K"),
            (999_999, "₹1M"),
            (1_200_000, "₹1.2M"),
            (-500, "-₹500"),
        ]

        for (value, expected) in cases {
            XCTAssertEqual(WidgetCurrencyFormatter.string(value), expected, "value: \(value)")
        }
    }
}
