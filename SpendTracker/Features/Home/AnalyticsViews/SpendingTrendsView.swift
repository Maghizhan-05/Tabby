import SwiftUI
import Charts

/// Spending trend over the last 30 days (line chart).
struct SpendingTrendsView: View {
    let expenses: [Expense]

    private var last30: [PeriodTotal] {
        SpendingAnalytics.totalsByPeriod(expenses, component: .day, count: 30, labelFormat: "M/d")
    }

    private var trendTotal: Decimal {
        last30.reduce(Decimal(0)) { $0 + $1.total }
    }

    var body: some View {
        VStack(spacing: 12) {
            AmountHeadline(title: "Last 30 Days", amount: trendTotal)

            if trendTotal == 0 {
                EmptyAnalytics()
            } else {
                Chart(last30) { item in
                    LineMark(
                        x: .value("Day", item.date),
                        y: .value("Total", NSDecimalNumber(decimal: item.total).doubleValue)
                    )
                    .foregroundStyle(Theme.accent)
                    .interpolationMethod(.catmullRom)

                    AreaMark(
                        x: .value("Day", item.date),
                        y: .value("Total", NSDecimalNumber(decimal: item.total).doubleValue)
                    )
                    .foregroundStyle(
                        LinearGradient(
                            colors: [Theme.accent.opacity(0.25), Theme.accent.opacity(0.02)],
                            startPoint: .top,
                            endPoint: .bottom
                        )
                    )
                    .interpolationMethod(.catmullRom)
                }
                .chartXAxis {
                    AxisMarks(values: .stride(by: .day, count: 7))
                }
                .frame(height: 160)
            }
        }
    }
}
