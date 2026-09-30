import Foundation

/// Shared constants for App Group container access and URL scheme deep-linking.
enum AppGroupConstants {
    /// App Group identifier shared by the app, widget, and app intents.
    static let appGroupID = "group.com.maghizhan.spendtracker"

    /// Custom URL scheme used for widget deep-links and intent routing.
    static let urlScheme = "spendtracker"

    /// Deep-link host that opens the quick-entry sheet.
    static let quickEntryHost = "quick-entry"

    /// Full deep-link URL that opens the quick-entry sheet.
    static var quickEntryURL: URL {
        URL(string: "\(urlScheme)://\(quickEntryHost)")!
    }

    /// SwiftData store filename inside the shared container.
    static let storeFileName = "SpendTracker.store"
}
