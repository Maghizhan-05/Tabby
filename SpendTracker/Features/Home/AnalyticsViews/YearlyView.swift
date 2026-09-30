import SwiftUI
import Charts

/// This year's spending by month (bar chart).
struct YearlyView: View {
    let expenses: [Expense]

    private var yearInterval: DateInterval {
        Calendar.current.dateInterval(of: .year, for: Date())!
    }

    private var yearExpenses: [Expense] {
        SpendingAnalytics.expenses(expenses, in: yearInterval)
    }

    private var byMonth: [PeriodTotal] {
        SpendingAnalytics.totalsByPeriod(expenses, component: .month, count: 12, labelFormat: "MMM")
    }

    var body: some View {
        VStack(spacing: 12) {
            AmountHeadline(title: "This Year", amount: SpendingAnalytics.total(yearExpenses))

            if SpendingAnalytics.total(yearExpenses) == 0 {
                EmptyAnalytics()
            } else {
                Chart(byMonth) { item in
                    BarMark(
                        x: .value("Month", item.label),
                        y: .value("Total", NSDecimalNumber(decimal: item.total).doubleValue)
                    )
                    .foregroundStyle(Theme.accent)
                    .cornerRadius(3)
                }
                .frame(height: 160)
            }
        }
    }
}
