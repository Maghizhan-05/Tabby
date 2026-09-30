import SwiftUI
import SwiftData

/// The top-half analytics canvas with a compact mode selector above a single
/// chart/ring canvas.
struct AnalyticsView: View {
    @Query(sort: \Expense.date, order: .reverse) private var expenses: [Expense]
    @ObservedObject var viewModel: HomeViewModel

    var body: some View {
        VStack(spacing: 14) {
            selector

            Group {
                switch viewModel.selectedMode {
                case .daily: DailyView(expenses: expenses)
                case .weekly: WeeklyView(expenses: expenses)
                case .monthly: MonthlyView(expenses: expenses)
                case .yearly: YearlyView(expenses: expenses)
                case .categories: CategoryBreakdownView(expenses: expenses)
                case .trends: SpendingTrendsView(expenses: expenses)
                }
            }
            .frame(maxWidth: .infinity)
        }
    }

    private var selector: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(AnalyticsMode.allCases) { mode in
                    let selected = viewModel.selectedMode == mode
                    Button {
                        withAnimation(.easeInOut(duration: 0.2)) {
                            viewModel.selectedMode = mode
                        }
                    } label: {
                        Text(mode.rawValue)
                            .font(.footnote.weight(.medium))
                            .foregroundStyle(selected ? .white : Theme.ink)
                            .padding(.horizontal, 14)
                            .padding(.vertical, 7)
                            .background(
                                selected ? Theme.accent : Color.white,
                                in: Capsule()
                            )
                            .overlay(Capsule().stroke(Theme.hairline, lineWidth: selected ? 0 : 1))
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.horizontal, 2)
        }
    }
}
