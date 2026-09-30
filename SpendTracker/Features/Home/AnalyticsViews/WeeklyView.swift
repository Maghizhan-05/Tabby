import SwiftUI
import Charts

/// This week's spending broken down by day (bar chart).
struct WeeklyView: View {
    let expenses: [Expense]

    private var weekInterval: DateInterval {
        Calendar.current.dateInterval(of: .weekOfYear, for: Date())!
    }

    private var weekExpenses: [Expense] {
        SpendingAnalytics.expenses(expenses, in: weekInterval)
    }

    private var byDay: [PeriodTotal] {
        SpendingAnalytics.totalsByPeriod(expenses, component: .day, count: 7, labelFormat: "EEE")
    }

    var body: some View {
        VStack(spacing: 12) {
            AmountHeadline(title: "This Week", amount: SpendingAnalytics.total(weekExpenses))

            if SpendingAnalytics.total(weekExpenses) == 0 {
                EmptyAnalytics()
            } else {
                Chart(byDay) { item in
                    BarMark(
                        x: .value("Day", item.label),
                        y: .value("Total", NSDecimalNumber(decimal: item.total).doubleValue)
                    )
                    .foregroundStyle(Theme.accent)
                    .cornerRadius(4)
                }
                .frame(height: 160)
            }
        }
    }
}
