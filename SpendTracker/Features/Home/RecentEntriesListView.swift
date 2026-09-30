import SwiftUI
import SwiftData

/// The calm recent-entries list for the bottom half of Home.
struct RecentEntriesListView: View {
    @Environment(\.modelContext) private var modelContext
    @Query(sort: \Expense.date, order: .reverse) private var expenses: [Expense]

    var body: some View {
        if expenses.isEmpty {
            VStack(spacing: 8) {
                Text("No entries yet")
                    .font(.subheadline)
                    .foregroundStyle(Theme.subtleInk)
                Text("Tap + to log your first expense.")
                    .font(.caption)
                    .foregroundStyle(Theme.subtleInk.opacity(0.8))
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        } else {
            List {
                ForEach(expenses) { expense in
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(expense.categoryName)
                                .font(.body.weight(.medium))
                                .foregroundStyle(Theme.ink)
                            Text(expense.date, format: .dateTime.month().day().hour().minute())
                                .font(.caption)
                                .foregroundStyle(Theme.subtleInk)
                        }
                        Spacer()
                        Text(CurrencyFormat.string(expense.amount))
                            .font(.body.weight(.semibold).monospacedDigit())
                            .foregroundStyle(Theme.ink)
                    }
                    .listRowBackground(Theme.paper)
                    .listRowSeparatorTint(Theme.hairline)
                }
                .onDelete(perform: delete)
            }
            .listStyle(.plain)
            .scrollContentBackground(.hidden)
        }
    }

    private func delete(at offsets: IndexSet) {
        for index in offsets {
            modelContext.delete(expenses[index])
        }
        try? modelContext.save()
    }
}
