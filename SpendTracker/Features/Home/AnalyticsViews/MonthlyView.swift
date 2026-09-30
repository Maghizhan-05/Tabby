import SwiftUI
import Charts

/// This month's spending by week (bar chart).
struct MonthlyView: View {
    let expenses: [Expense]

    private var monthInterval: DateInterval {
        Calendar.current.dateInterval(of: .month, for: Date())!
    }

    private var monthExpenses: [Expense] {
        SpendingAnalytics.expenses(expenses, in: monthInterval)
    }

    private var byWeek: [PeriodTotal] {
        SpendingAnalytics.totalsByPeriod(expenses, component: .weekOfYear, count: 5, labelFormat: "'W'w")
    }

    var body: some View {
        VStack(spacing: 12) {
            AmountHeadline(title: "This Month", amount: SpendingAnalytics.total(monthExpenses))

            if SpendingAnalytics.total(monthExpenses) == 0 {
                EmptyAnalytics()
            } else {
                Chart(byWeek) { item in
                    BarMark(
                        x: .value("Week", item.label),
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
