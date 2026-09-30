import SwiftUI
import Charts

/// All-time category breakdown: donut ring + legend.
struct CategoryBreakdownView: View {
    let expenses: [Expense]

    private var totals: [CategoryTotal] {
        SpendingAnalytics.totalsByCategory(expenses)
    }

    var body: some View {
        VStack(spacing: 12) {
            AmountHeadline(title: "By Category", amount: SpendingAnalytics.total(expenses))

            if totals.isEmpty {
                EmptyAnalytics()
            } else {
                HStack(spacing: 16) {
                    Chart(totals) { item in
                        SectorMark(
                            angle: .value("Total", NSDecimalNumber(decimal: item.total).doubleValue),
                            innerRadius: .ratio(0.6),
                            angularInset: 1.5
                        )
                        .foregroundStyle(item.color)
                        .cornerRadius(3)
                    }
                    .frame(width: 130, height: 130)

                    VStack(alignment: .leading, spacing: 6) {
                        ForEach(totals.prefix(6)) { item in
                            HStack(spacing: 6) {
                                Circle().fill(item.color).frame(width: 8, height: 8)
                                Text(item.category)
                                    .font(.caption)
                                    .foregroundStyle(Theme.ink)
                                Spacer()
                                Text(CurrencyFormat.string(item.total))
                                    .font(.caption.weight(.medium))
                                    .foregroundStyle(Theme.subtleInk)
                            }
                        }
                    }
                }
                .frame(height: 160)
            }
        }
    }
}
