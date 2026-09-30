import Foundation
import SwiftUI

/// A category aggregate for chart rendering.
struct CategoryTotal: Identifiable {
    let id = UUID()
    let category: String
    let total: Decimal
    let color: Color
}

/// A time-bucketed aggregate for bar/line charts.
struct PeriodTotal: Identifiable {
    let id = UUID()
    let label: String
    let date: Date
    let total: Decimal
}

/// Pure aggregation helpers over a list of expenses. Kept free of SwiftUI/SwiftData
/// so they can be unit-tested and reused by the widget.
enum SpendingAnalytics {

    static func totalsByCategory(_ expenses: [Expense]) -> [CategoryTotal] {
        var buckets: [String: Decimal] = [:]
        for e in expenses {
            buckets[e.categoryName, default: 0] += e.amount
        }
        let sorted = buckets.sorted { $0.value > $1.value }
        return sorted.enumerated().map { index, pair in
            CategoryTotal(
                category: pair.key,
                total: pair.value,
                color: Theme.ringColors[index % Theme.ringColors.count]
            )
        }
    }

    static func total(_ expenses: [Expense]) -> Decimal {
        expenses.reduce(0) { $0 + $1.amount }
    }

    static func expenses(_ expenses: [Expense], in interval: DateInterval) -> [Expense] {
        expenses.filter { interval.contains($0.date) }
    }

    /// Groups expenses by a calendar component, producing ordered period totals.
    static func totalsByPeriod(
        _ expenses: [Expense],
        component: Calendar.Component,
        count: Int,
        labelFormat: String
    ) -> [PeriodTotal] {
        let calendar = Calendar.current
        let now = Date()
        let formatter = DateFormatter()
        formatter.dateFormat = labelFormat

        var results: [PeriodTotal] = []
        for offset in stride(from: count - 1, through: 0, by: -1) {
            guard let bucketDate = calendar.date(byAdding: component, value: -offset, to: now) else { continue }
            guard let interval = calendar.dateInterval(of: bucketComponent(component), for: bucketDate) else { continue }
            let total = expenses
                .filter { interval.contains($0.date) }
                .reduce(Decimal(0)) { $0 + $1.amount }
            results.append(PeriodTotal(label: formatter.string(from: bucketDate), date: bucketDate, total: total))
        }
        return results
    }

    private static func bucketComponent(_ component: Calendar.Component) -> Calendar.Component {
        switch component {
        case .day: return .day
        case .weekOfYear: return .weekOfYear
        case .month: return .month
        case .year: return .year
        default: return .day
        }
    }
}
