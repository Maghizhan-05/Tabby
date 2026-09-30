import Foundation
import ActivityKit

/// Shared ActivityAttributes for the expense confirmation Live Activity.
/// Used by both the app (to start/end) and the widget extension (to render).
struct ExpenseConfirmationAttributes: ActivityAttributes {
    public struct ContentState: Codable, Hashable {
        var amount: Double
        var category: String
    }

    // Static metadata (none required beyond the content state).
    var title: String = "Expense Logged"
}
