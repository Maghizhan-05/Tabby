import AppIntents

/// Opens the app straight to the quick-entry sheet. Bindable to Back Tap
/// via the Shortcuts app.
struct LogExpenseIntent: AppIntent {
    static var title: LocalizedStringResource = "Log Expense"
    static var description = IntentDescription("Open Tabby to quickly log a new expense.")

    // Bring the app to the foreground so the quick-entry sheet can appear.
    static var openAppWhenRun: Bool = true

    @MainActor
    func perform() async throws -> some IntentResult {
        QuickEntryLauncher.shared.requestQuickEntry()
        return .result()
    }
}

/// Bridges the intent to the running app's UI state.
final class QuickEntryLauncher {
    static let shared = QuickEntryLauncher()
    private init() {}

    var onRequest: (() -> Void)?

    func requestQuickEntry() {
        onRequest?()
    }
}
