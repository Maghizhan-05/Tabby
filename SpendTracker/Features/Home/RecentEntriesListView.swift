import SwiftUI
import SwiftData

/// Recent spending is deliberately quiet so the analytics visual remains primary.
struct RecentEntriesListView: View {
    @Environment(\.modelContext) private var modelContext
    @Query(sort: \Expense.date, order: .reverse) private var expenses: [Expense]

    var body: some View {
        if expenses.isEmpty {
            VStack(spacing: 10) {
                TabbyOrbit(size: 32, lineWidth: 2)
                Text("No spends yet")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(Theme.ink)
                Text("Your next tab starts with one tap.")
                    .font(.caption)
                    .foregroundStyle(Theme.subtleInk)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        } else {
            List {
                ForEach(expenses) { expense in
                    HStack(spacing: 12) {
                        Circle()
                            .fill(Theme.accent.opacity(0.16))
                            .frame(width: 30, height: 30)
                            .overlay(Image(systemName: "circle.fill").font(.system(size: 6)).foregroundStyle(Theme.accent))
                        VStack(alignment: .leading, spacing: 3) {
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
                    .padding(.vertical, 5)
                    .listRowBackground(Color.clear)
                    .listRowSeparatorTint(Theme.hairline)
                }
                .onDelete(perform: delete)
            }
            .listStyle(.plain)
            .scrollContentBackground(.hidden)
        }
    }

    private func delete(at offsets: IndexSet) {
        for index in offsets { modelContext.delete(expenses[index]) }
        try? modelContext.save()
    }
}
