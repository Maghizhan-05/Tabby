import AppIntents
import Foundation
import SwiftData

/// The analytics presentation selectable from the widget configuration sheet.
enum AnalyticsWidgetMode: String, AppEnum, CaseIterable {
    case daily
    case weekly
    case monthly
    case yearly
    case categories
    case trends

    static var typeDisplayRepresentation: TypeDisplayRepresentation = "Analytics Mode"

    static var caseDisplayRepresentations: [AnalyticsWidgetMode: DisplayRepresentation] = [
        .daily: "Daily",
        .weekly: "Weekly",
        .monthly: "Monthly",
        .yearly: "Yearly",
        .categories: "Categories",
        .trends: "Trends",
    ]
}

/// Configuration for the single, mode-selectable analytics widget.
struct AnalyticsModeIntent: WidgetConfigurationIntent {
    static var title: LocalizedStringResource = "Analytics Mode"
    static var description = IntentDescription("Choose the spending analysis shown by the widget.")

    @Parameter(title: "Mode", default: .daily)
    var mode: AnalyticsWidgetMode

    init() {
        mode = .daily
    }

    init(mode: AnalyticsWidgetMode) {
        self.mode = mode
    }
}

/// Reads the shared App-Group SwiftData store for widget rendering.
enum WidgetDataProvider {

    static let appGroupID = "group.com.maghizhan.spendtracker"
    static let storeFileName = "SpendTracker.store"

    private static let container: ModelContainer? = {
        let schema = Schema([Expense.self, Category.self, Friend.self, UserProfile.self])
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

    struct TrendTotal: Identifiable {
        let date: Date
        let label: String
        let total: Double

        var id: Date { date }
    }

    struct Snapshot {
        let mode: AnalyticsWidgetMode
        let title: String
        let total: Double
        let slices: [RingSlice]
        let trendTotals: [TrendTotal]
        let trendDelta: Double

        /// Compatibility for the original Daily widget entry and its tests.
        var todayTotal: Double { total }

        init(
            mode: AnalyticsWidgetMode,
            title: String,
            total: Double,
            slices: [RingSlice] = [],
            trendTotals: [TrendTotal] = [],
            trendDelta: Double = 0
        ) {
            self.mode = mode
            self.title = title
            self.total = total
            self.slices = slices
            self.trendTotals = trendTotals
            self.trendDelta = trendDelta
        }
    }

    /// Legacy Daily wrapper retained for existing callers.
    static func currentSnapshot() -> Snapshot {
        currentSnapshot(for: .daily)
    }

    static func currentSnapshot(for mode: AnalyticsWidgetMode) -> Snapshot {
        guard let container else {
            return emptySnapshot(for: mode)
        }
        return snapshot(from: container, mode: mode)
    }

    /// Legacy Daily wrapper retained for existing callers and tests.
    static func snapshot(from container: ModelContainer, now: Date = Date()) -> Snapshot {
        snapshot(from: container, mode: .daily, now: now)
    }

    /// Pure snapshot computation from any container. Exposed so tests can drive
    /// the exact code path the widget uses against a shared-store-backed
    /// container and assert the widget sees app-written expenses.
    static func snapshot(
        from container: ModelContainer,
        mode: AnalyticsWidgetMode,
        now: Date = Date()
    ) -> Snapshot {
        let context = ModelContext(container)
        let descriptor = FetchDescriptor<Expense>()
        let expenses = (try? context.fetch(descriptor)) ?? []

        if mode == .trends {
            return trendSnapshot(expenses: expenses, now: now)
        }

        let filtered = filteredExpenses(in: interval(for: mode, now: now), from: expenses)
        let slices = ringSlices(from: filtered)
        return Snapshot(
            mode: mode,
            title: title(for: mode),
            total: total(of: filtered),
            slices: slices
        )
    }

    private static func emptySnapshot(for mode: AnalyticsWidgetMode) -> Snapshot {
        if mode == .trends {
            return Snapshot(mode: mode, title: title(for: mode), total: 0, trendTotals: [], trendDelta: 0)
        }
        return Snapshot(mode: mode, title: title(for: mode), total: 0, slices: [])
    }

    private static func interval(for mode: AnalyticsWidgetMode, now: Date) -> DateInterval? {
        let calendar = Calendar.current
        switch mode {
        case .daily:
            return calendar.dateInterval(of: .day, for: now)
        case .weekly:
            return calendar.dateInterval(of: .weekOfYear, for: now)
        case .monthly:
            return calendar.dateInterval(of: .month, for: now)
        case .yearly:
            return calendar.dateInterval(of: .year, for: now)
        case .categories, .trends:
            return nil
        }
    }

    private static func filteredExpenses(in interval: DateInterval?, from expenses: [Expense]) -> [Expense] {
        guard let interval else { return expenses }
        return expenses.filter { interval.contains($0.date) }
    }

    private static func title(for mode: AnalyticsWidgetMode) -> String {
        switch mode {
        case .daily: "Today"
        case .weekly: "This Week"
        case .monthly: "This Month"
        case .yearly: "This Year"
        case .categories: "By Category"
        case .trends: "Weekly Trend"
        }
    }

    private static func ringSlices(from expenses: [Expense]) -> [RingSlice] {
        var buckets: [String: Double] = [:]
        for expense in expenses {
            buckets[expense.categoryName, default: 0] += amount(of: expense)
        }
        let ranked = buckets.sorted { $0.value > $1.value }
        // Keep the ring faithful to the center total: everything beyond the top
        // five categories is aggregated into a single "Other" slice rather than
        // dropped, so slice totals always sum to the displayed total.
        guard ranked.count > 6 else {
            return ranked.map { RingSlice(category: $0.key, total: $0.value) }
        }
        let top = ranked.prefix(5).map { RingSlice(category: $0.key, total: $0.value) }
        let remainder = ranked.dropFirst(5).reduce(0.0) { $0 + $1.value }
        return top + [RingSlice(category: "Other", total: remainder)]
    }

    private static func trendSnapshot(expenses: [Expense], now: Date) -> Snapshot {
        let calendar = Calendar.current
        let formatter = DateFormatter()
        formatter.locale = .current
        formatter.setLocalizedDateFormatFromTemplate("MMM d")

        let weeks: [TrendTotal] = (0..<6).compactMap { offset in
            guard let date = calendar.date(byAdding: .weekOfYear, value: offset - 5, to: now),
                  let interval = calendar.dateInterval(of: .weekOfYear, for: date) else {
                return nil
            }
            return TrendTotal(
                date: interval.start,
                label: formatter.string(from: interval.start),
                total: total(of: expenses.filter { interval.contains($0.date) })
            )
        }
        let current = weeks.last?.total ?? 0
        let previous = weeks.dropLast().last?.total ?? 0
        return Snapshot(
            mode: .trends,
            title: title(for: .trends),
            total: current,
            trendTotals: weeks,
            trendDelta: current - previous
        )
    }

    private static func total(of expenses: [Expense]) -> Double {
        expenses.reduce(0.0) { $0 + amount(of: $1) }
    }

    private static func amount(of expense: Expense) -> Double {
        NSDecimalNumber(decimal: expense.amount).doubleValue
    }
}
