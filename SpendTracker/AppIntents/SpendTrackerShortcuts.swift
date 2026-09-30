import AppIntents

/// Exposes app shortcuts (including LogExpenseIntent) so they can be bound to
/// Back Tap via Settings > Accessibility > Touch > Back Tap.
struct SpendTrackerShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(
            intent: LogExpenseIntent(),
            phrases: [
                "Log an expense in \(.applicationName)",
                "Add a \(.applicationName) expense",
                "Open \(.applicationName) quick entry",
            ],
            shortTitle: "Log Expense",
            systemImageName: "plus.circle"
        )
    }
}
