import Foundation
import SwiftData

/// Provides a shared ModelContainer stored in the App Group container so the
/// app, widget, and app intents all read/write the same SwiftData store.
enum SharedModelContainer {

    /// Why the shared store could not be opened in the App Group container.
    ///
    /// Surfaced to the user in Release instead of silently falling back to a
    /// per-process store. A silent fallback looks like working software while
    /// the widget reads a different database than the app — the exact
    /// shared-store desync this project has already hit once, and it leaves no
    /// crashlog to diagnose.
    enum ConfigurationError: Equatable {
        case appGroupUnavailable(identifier: String)
        case storeOpenFailed(identifier: String, underlying: String)

        var message: String {
            switch self {
            case let .appGroupUnavailable(identifier):
                return """
                Shared storage is unavailable: the App Group container for \
                '\(identifier)' could not be opened. The app and its widget \
                would read separate databases, so saving is disabled to avoid \
                losing data. Reinstall the app or check the App Group \
                entitlement.
                """
            case let .storeOpenFailed(identifier, underlying):
                return """
                Shared storage could not be opened in the App Group container \
                for '\(identifier)': \(underlying). Saving is disabled to \
                avoid losing data. Reinstall the app.
                """
            }
        }

        /// Developer-facing hint; not shown in Release UI.
        var developerHint: String {
            switch self {
            case .appGroupUnavailable:
                return """
                On the simulator this happens when the build skips the \
                entitlement codesign pass (CODE_SIGNING_ALLOWED=NO). Ad-hoc \
                signing (CODE_SIGN_IDENTITY="-") provisions the container — \
                use ./run.sh, which re-signs the widget and host app.
                """
            case .storeOpenFailed:
                return "The container exists but the store file could not be opened."
            }
        }
    }

    /// Non-nil when the shared store is NOT backed by the App Group container.
    ///
    /// The app reads this at launch and shows a blocking configuration screen,
    /// so the failure is loud in Release as well as Debug. Set before `shared`
    /// finishes initializing; read it only after touching `shared`.
    private(set) nonisolated(unsafe) static var configurationError: ConfigurationError?

    static let shared: ModelContainer = {
        let schema = Schema([
            Expense.self,
            Category.self,
            Friend.self,
            UserProfile.self,
        ])

        guard let groupURL = FileManager.default.containerURL(
            forSecurityApplicationGroupIdentifier: AppGroupConstants.appGroupID
        ) else {
            // The App Group container is NOT provisioned. Do not pretend to
            // work: record the error (the UI blocks on it) and keep the data
            // in memory so nothing is written to a store the widget can't see.
            let error = ConfigurationError.appGroupUnavailable(
                identifier: AppGroupConstants.appGroupID
            )
            configurationError = error
            print("⚠️ SharedModelContainer: \(error.message)\n\(error.developerHint)")
            return inMemoryContainer(schema: schema)
        }

        let storeURL = groupURL.appendingPathComponent(AppGroupConstants.storeFileName)
        let configuration = ModelConfiguration(schema: schema, url: storeURL)

        let container: ModelContainer
        do {
            container = try ModelContainer(for: schema, configurations: [configuration])
        } catch {
            // The container exists but the store won't open. Same reasoning:
            // fail loudly rather than diverge into a per-process store.
            let configError = ConfigurationError.storeOpenFailed(
                identifier: AppGroupConstants.appGroupID,
                underlying: error.localizedDescription
            )
            configurationError = configError
            print("⚠️ SharedModelContainer: \(configError.message)")
            return inMemoryContainer(schema: schema)
        }

        seedDefaultCategoriesIfNeeded(container: container)
        return container
    }()

    /// Last-resort container so the app can render its own error screen instead
    /// of crashing before any UI exists.
    private static func inMemoryContainer(schema: Schema) -> ModelContainer {
        let memoryConfig = ModelConfiguration(schema: schema, isStoredInMemoryOnly: true)
        // swiftlint:disable:next force_try
        return try! ModelContainer(for: schema, configurations: [memoryConfig])
    }

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
