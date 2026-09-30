import Foundation
import SwiftData

/// Provides a shared ModelContainer stored in the App Group container so the
/// app, widget, and app intents all read/write the same SwiftData store.
enum SharedModelContainer {

    static let shared: ModelContainer = {
        let schema = Schema([
            Expense.self,
            Category.self,
            UserProfile.self,
        ])

        let configuration: ModelConfiguration
        if let groupURL = FileManager.default.containerURL(
            forSecurityApplicationGroupIdentifier: AppGroupConstants.appGroupID
        ) {
            let storeURL = groupURL.appendingPathComponent(AppGroupConstants.storeFileName)
            configuration = ModelConfiguration(schema: schema, url: storeURL)
        } else {
            // The App Group container is NOT provisioned. This is a real
            // misconfiguration: the app and widget will silently diverge into
            // SEPARATE per-process default stores, so the widget shows ₹0 while
            // the app shows real data. On the simulator this happens when the
            // build skips the entitlement codesign pass (CODE_SIGNING_ALLOWED=NO);
            // ad-hoc signing (CODE_SIGN_IDENTITY="-") provisions the container.
            let message = """
            ⚠️ SharedModelContainer: App Group container for \
            '\(AppGroupConstants.appGroupID)' is nil. Falling back to a \
            per-process default store — the widget and app will NOT share data. \
            Ensure the App Group entitlement is applied (ad-hoc sign the \
            simulator build; do not disable code signing).
            """
            print(message)
            assertionFailure(message)
            configuration = ModelConfiguration(schema: schema)
        }

        let container: ModelContainer
        do {
            container = try ModelContainer(for: schema, configurations: [configuration])
        } catch {
            // Last-resort in-memory container so the app never crashes on launch.
            let memoryConfig = ModelConfiguration(schema: schema, isStoredInMemoryOnly: true)
            // swiftlint:disable:next force_try
            container = try! ModelContainer(for: schema, configurations: [memoryConfig])
        }

        seedDefaultCategoriesIfNeeded(container: container)
        return container
    }()

    static let defaultCategoryNames = [
        "Food", "Transport", "Groceries", "Bills",
        "Shopping", "Entertainment", "Health", "Other",
    ]

    /// Seeds default categories on first run using a background context so it
    /// is safe to call from the container's non-isolated initializer.
    private static func seedDefaultCategoriesIfNeeded(container: ModelContainer) {
        let context = ModelContext(container)
        let descriptor = FetchDescriptor<Category>()
        let existingCount = (try? context.fetchCount(descriptor)) ?? 0
        guard existingCount == 0 else { return }

        for (index, name) in defaultCategoryNames.enumerated() {
            // Default categories are local-only presets; mark them .synced so the
            // SyncEngine does not push them every run. User-created categories
            // default to .local and are pushed on the next sync.
            let category = Category(name: name, isDefault: true, sortOrder: index, syncState: .synced)
            context.insert(category)
        }
        try? context.save()
    }
}
