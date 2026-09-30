import Foundation
import ActivityKit

/// Starts a short confirmation Live Activity on successful submit and
/// auto-ends it. Guards availability so it degrades gracefully.
final class LiveActivityManager {
    static let shared = LiveActivityManager()
    private init() {}

    func startConfirmation(amount: Decimal, category: String) {
        guard #available(iOS 16.1, *) else { return }
        guard ActivityAuthorizationInfo().areActivitiesEnabled else { return }

        let amountDouble = NSDecimalNumber(decimal: amount).doubleValue
        let state = ExpenseConfirmationAttributes.ContentState(
            amount: amountDouble,
            category: category
        )
        let attributes = ExpenseConfirmationAttributes()

        do {
            let activity: Activity<ExpenseConfirmationAttributes>
            if #available(iOS 16.2, *) {
                activity = try Activity.request(
                    attributes: attributes,
                    content: .init(state: state, staleDate: nil)
                )
            } else {
                activity = try Activity.request(
                    attributes: attributes,
                    contentState: state
                )
            }
            // Auto-end after a few seconds.
            Task {
                try? await Task.sleep(nanoseconds: 4_000_000_000)
                if #available(iOS 16.2, *) {
                    await activity.end(nil, dismissalPolicy: .immediate)
                } else {
                    await activity.end(dismissalPolicy: .immediate)
                }
            }
        } catch {
            // Non-fatal: confirmation ring simply won't appear.
        }
    }
}
