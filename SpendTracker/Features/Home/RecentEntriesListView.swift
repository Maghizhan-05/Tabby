import SwiftUI
import SwiftData
import WidgetKit

enum RecentEntryPresentation {
    static func title(for expense: Expense) -> String {
        expense.note ?? "\(expense.categoryName) expense"
    }

    static func category(for expense: Expense) -> String {
        expense.categoryName
    }
}

/// Recent spending is deliberately quiet so the analytics visual remains primary.
struct RecentEntriesListView: View {
    @Environment(\.modelContext) private var modelContext
    @Environment(\.scenePhase) private var scenePhase
    @Query(sort: \Expense.date, order: .reverse) private var expenses: [Expense]
    @State private var expenseBeingEdited: Expense?

    var body: some View {
        Group {
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
                                Text(RecentEntryPresentation.title(for: expense))
                                    .font(.body.weight(.medium))
                                    .foregroundStyle(Theme.ink)
                                    .lineLimit(1)
                                HStack(spacing: 8) {
                                    Text(RecentEntryPresentation.category(for: expense))
                                        .font(.caption.weight(.medium))
                                        .foregroundStyle(Theme.categoryAccent(for: expense.categoryName).color.opacity(0.84))
                                    Text(expense.date, format: .dateTime.month().day().hour().minute())
                                        .font(.caption)
                                        .foregroundStyle(Theme.subtleInk)
                                }
                            }
                            Spacer()
                            Text(CurrencyFormat.string(expense.amount))
                                .font(.body.weight(.semibold).monospacedDigit())
                                .foregroundStyle(Theme.ink)
                        }
                        .contentShape(Rectangle())
                        .onTapGesture { expenseBeingEdited = expense }
                        .padding(.vertical, 5)
                        .listRowBackground(Color.clear)
                        .listRowSeparatorTint(Theme.hairline)
                        // A swipe reveals BOTH actions: Delete sits at the edge
                        // (native position), Edit beside it. Tapping the row edits.
                        .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                            Button(role: .destructive) {
                                delete(expense)
                            } label: {
                                Label("Delete", systemImage: "trash")
                            }
                            Button {
                                expenseBeingEdited = expense
                            } label: {
                                Label("Edit", systemImage: "pencil")
                            }
                            .tint(Theme.accent)
                        }
                        .accessibilityAction(named: "Edit") {
                            expenseBeingEdited = expense
                        }
                        .accessibilityAction(named: "Delete") {
                            delete(expense)
                        }
                    }
                    .onDelete(perform: delete)
                }
                .listStyle(.plain)
                .scrollContentBackground(.hidden)
            }
        }
        .sheet(item: $expenseBeingEdited) { expense in
            ExpenseEditSheetView(expense: expense)
        }
        // Lifecycle retry: any edit left `.dirty` by a failed push is retried
        // when Recent Activity appears or the app returns to the foreground.
        .task { await pushPendingExpenses() }
        .onChange(of: scenePhase) { _, phase in
            guard phase == .active else { return }
            Task { await pushPendingExpenses() }
        }
    }

    private func pushPendingExpenses() async {
        await SyncEngine(
            modelContext: modelContext,
            expenseRepository: SupabaseExpenseRepository(),
            categoryRepository: SupabaseCategoryRepository()
        ).pushUnsyncedExpenses()
    }

    private func delete(at offsets: IndexSet) {
        commitDeletion(of: offsets.map { expenses[$0] })
    }

    private func delete(_ expense: Expense) {
        commitDeletion(of: [expense])
    }

    private func commitDeletion(of doomed: [Expense]) {
        for expense in doomed { modelContext.delete(expense) }
        try? modelContext.save()
        // Keep the widget's shared-store view in sync after a deletion.
        WidgetCenter.shared.reloadAllTimelines()
    }
}
