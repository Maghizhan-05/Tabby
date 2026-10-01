import SwiftUI
import SwiftData

/// The analytics canvas: one visual at a time, with an intentionally compact selector.
struct AnalyticsView: View {
    @Query(sort: \Expense.date, order: .reverse) private var allExpenses: [Expense]
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @EnvironmentObject private var auth: AuthViewModel
    @ObservedObject var viewModel: HomeViewModel

    /// Analytics only ever aggregates the signed-in account's expenses.
    private var expenses: [Expense] {
        ExpenseOwnership.visibleExpenses(allExpenses, activeOwnerId: auth.session?.userId)
    }

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
            .id(viewModel.selectedMode)
            .transition(.opacity.combined(with: .scale(scale: 0.98)))
            .frame(maxWidth: .infinity)
        }
    }

    private var selector: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 5) {
                ForEach(AnalyticsMode.allCases) { mode in
                    let selected = viewModel.selectedMode == mode
                    Button {
                        if reduceMotion {
                            viewModel.selectedMode = mode
                        } else {
                            withAnimation(.spring(response: 0.38, dampingFraction: 0.82)) {
                                viewModel.selectedMode = mode
                            }
                        }
                    } label: {
                        Text(mode.rawValue)
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(selected ? Theme.paper : Theme.subtleInk)
                            .padding(.horizontal, 12)
                            .padding(.vertical, 8)
                            .background(selected ? Theme.accent : Color.clear, in: Capsule())
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(4)
            .background(Theme.elevatedSurface.opacity(0.82), in: Capsule())
        }
    }
}
