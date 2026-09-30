import SwiftUI
import Charts

/// Today's spending: category donut + running total.
struct DailyView: View {
    let expenses: [Expense]

    private var todays: [Expense] {
        let interval = Calendar.current.dateInterval(of: .day, for: Date())!
        return SpendingAnalytics.expenses(expenses, in: interval)
    }

    private var totals: [CategoryTotal] {
        SpendingAnalytics.totalsByCategory(todays)
    }

    var body: some View {
        VStack(spacing: 12) {
            AmountHeadline(
                title: "Today",
                amount: SpendingAnalytics.total(todays)
            )

            if totals.isEmpty {
                EmptyAnalytics()
            } else {
                Chart(totals) { item in
                    SectorMark(
                        angle: .value("Total", NSDecimalNumber(decimal: item.total).doubleValue),
                        innerRadius: .ratio(0.62),
                        angularInset: 1.5
                    )
                    .foregroundStyle(item.color)
                    .cornerRadius(3)
                }
                .frame(height: 160)
            }
        }
    }
}
