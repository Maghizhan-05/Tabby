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
            // Fallback to default (e.g. App Group not provisioned in this environment).
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
            let category = Category(name: name, isDefault: true, sortOrder: index)
            context.insert(category)
        }
        try? context.save()
    }
}
