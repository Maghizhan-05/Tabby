import Foundation
import SwiftData

/// Reads the shared App-Group SwiftData store for widget rendering.
enum WidgetDataProvider {

    static let appGroupID = "group.com.maghizhan.spendtracker"
    static let storeFileName = "SpendTracker.store"

    private static let container: ModelContainer? = {
        let schema = Schema([Expense.self, Category.self, UserProfile.self])
        guard let groupURL = FileManager.default.containerURL(
            forSecurityApplicationGroupIdentifier: appGroupID
        ) else {
            return nil
        }
        let storeURL = groupURL.appendingPathComponent(storeFileName)
        let config = ModelConfiguration(schema: schema, url: storeURL)
        return try? ModelContainer(for: schema, configurations: [config])
    }()

    struct RingSlice: Identifiable {
        let id = UUID()
        let category: String
        let total: Double
    }

    struct Snapshot {
        let todayTotal: Double
        let slices: [RingSlice]
    }

    static func currentSnapshot() -> Snapshot {
        guard let container else {
            return Snapshot(todayTotal: 0, slices: [])
        }
        return snapshot(from: container)
    }

    /// Pure snapshot computation from any container. Exposed so tests can drive
    /// the exact code path the widget uses against a shared-store-backed
    /// container and assert the widget sees app-written expenses.
    static func snapshot(from container: ModelContainer, now: Date = Date()) -> Snapshot {
        let context = ModelContext(container)
        let descriptor = FetchDescriptor<Expense>()
        let expenses = (try? context.fetch(descriptor)) ?? []

        let calendar = Calendar.current
        let dayInterval = calendar.dateInterval(of: .day, for: now)
        let todays = expenses.filter { dayInterval?.contains($0.date) ?? false }
        let todayTotal = todays.reduce(0.0) { $0 + NSDecimalNumber(decimal: $1.amount).doubleValue }

        var buckets: [String: Double] = [:]
        for e in todays {
            buckets[e.categoryName, default: 0] += NSDecimalNumber(decimal: e.amount).doubleValue
        }
        let slices = buckets
            .sorted { $0.value > $1.value }
            .prefix(6)
            .map { RingSlice(category: $0.key, total: $0.value) }

        return Snapshot(todayTotal: todayTotal, slices: Array(slices))
    }
}
