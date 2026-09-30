import SwiftUI

/// All-time category breakdown: donut ring + legend.
struct CategoryBreakdownView: View {
    let expenses: [Expense]
    @State private var selectedCategory: String?
    @FocusState private var focusedLegendCategory: String?

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
                    SelectableDonutChart(
                        totals: totals,
                        selectedCategory: $selectedCategory,
                        innerRadius: 0.6
                    )
                    .frame(width: 130, height: 130)

                    VStack(alignment: .leading, spacing: 6) {
                        ForEach(totals.prefix(6)) { item in
                            let selected = selectedCategory == item.category
                            Button {
                                selectedCategory = selected ? nil : item.category
                            } label: {
                                HStack(spacing: 6) {
                                    Circle().fill(item.color).frame(width: 8, height: 8)
                                    Text(item.category)
                                        .font(.caption.weight(selected ? .semibold : .regular))
                                        .foregroundStyle(Theme.ink)
                                    Spacer()
                                    Text(CurrencyFormat.string(item.total))
                                        .font(.caption.weight(.medium))
                                        .foregroundStyle(Theme.subtleInk)
                                }
                                .padding(.horizontal, 5)
                                .padding(.vertical, 3)
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
                .frame(height: 160)
                .onKeyPress(.escape) {
                    selectedCategory = nil
                    focusedLegendCategory = nil
                    return .handled
                }
            }
        }
    }
}
