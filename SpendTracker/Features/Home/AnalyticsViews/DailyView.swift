import SwiftUI

/// Today's spending: category donut + running total.
struct DailyView: View {
    let expenses: [Expense]
    @State private var selectedCategory: String?
    @FocusState private var focusedLegendCategory: String?

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
                VStack(spacing: 8) {
                    SelectableDonutChart(
                        totals: totals,
                        selectedCategory: $selectedCategory,
                        innerRadius: 0.62
                    )
                    .frame(height: 160)

                    LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], alignment: .leading, spacing: 4) {
                        ForEach(totals.prefix(6)) { item in
                            let selected = selectedCategory == item.category
                            Button {
                                selectedCategory = selected ? nil : item.category
                            } label: {
                                HStack(spacing: 5) {
                                    Circle().fill(item.color).frame(width: 7, height: 7)
                                    Text(item.category)
                                        .font(.caption2.weight(selected ? .semibold : .regular))
                                        .foregroundStyle(Theme.ink)
                                        .lineLimit(1)
                                    Spacer(minLength: 0)
                                }
                                .padding(.horizontal, 5)
                                .padding(.vertical, 4)
                                .background(selected ? Theme.elevatedSurface : Color.clear, in: Theme.controlShape)
                                .overlay(Theme.controlShape.stroke(selected ? Theme.hairline : .clear))
                            }
                            .buttonStyle(.plain)
                            .focused($focusedLegendCategory, equals: item.category)
                            .accessibilityLabel("\(item.category), \(CurrencyFormat.string(item.total))")
                            .accessibilityAddTraits(selected ? .isSelected : [])
                        }
                    }
                }
                .onKeyPress(.escape) {
                    selectedCategory = nil
                    focusedLegendCategory = nil
                    return .handled
                }
            }
        }
    }
}
