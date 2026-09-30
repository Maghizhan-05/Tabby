import SwiftUI
import SwiftData

struct QuickEntrySheetView: View {
    @Environment(\.modelContext) private var modelContext
    @Environment(\.dismiss) private var dismiss
    @Query(sort: \Category.sortOrder) private var categories: [Category]
    @StateObject private var viewModel = QuickEntryViewModel()
    @FocusState private var amountFocused: Bool
    @State private var showCategoryPicker = false

    var body: some View {
        NavigationStack {
            ZStack {
                Theme.paper.ignoresSafeArea()

                VStack(spacing: 28) {
                    amountField
                    categoryField
                    datePicker
                    Spacer()
                    submitButton
                }
                .padding(24)
            }
            .navigationTitle("New Expense")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("Cancel") { dismiss() }
                }
            }
            .tint(Theme.accent)
        }
        .onAppear { amountFocused = true }
        .sheet(isPresented: $showCategoryPicker) {
            NavigationStack {
                CategoryPickerView(query: $viewModel.categoryQuery) { name in
                    viewModel.categoryQuery = name
                    showCategoryPicker = false
                }
                .searchable(text: $viewModel.categoryQuery, prompt: "Search or add category")
                .navigationTitle("Category")
                .navigationBarTitleDisplayMode(.inline)
            }
            .presentationDetents([.medium, .large])
        }
    }

    private var amountField: some View {
        VStack(spacing: 6) {
            Text("AMOUNT")
                .font(.caption.weight(.semibold))
                .foregroundStyle(Theme.subtleInk)
                .tracking(1)
            TextField("0", text: $viewModel.amountText)
                .font(.system(size: 56, weight: .bold, design: .rounded))
                .foregroundStyle(Theme.ink)
                .multilineTextAlignment(.center)
                .keyboardType(.decimalPad)
                .focused($amountFocused)
        }
        .padding(.top, 12)
    }

    private var categoryField: some View {
        Button {
            showCategoryPicker = true
        } label: {
            HStack {
                Text(viewModel.categoryQuery.isEmpty ? "Select category" : viewModel.categoryQuery)
                    .foregroundStyle(viewModel.categoryQuery.isEmpty ? Theme.subtleInk : Theme.ink)
                Spacer()
                Image(systemName: "chevron.right").foregroundStyle(Theme.subtleInk)
            }
            .padding(16)
            .background(Color.white, in: RoundedRectangle(cornerRadius: 12))
            .overlay(RoundedRectangle(cornerRadius: 12).stroke(Theme.hairline))
        }
        .buttonStyle(.plain)
    }

    private var datePicker: some View {
        DatePicker(
            "Date",
            selection: $viewModel.selectedDate,
            displayedComponents: [.date, .hourAndMinute]
        )
        .datePickerStyle(.compact)
        .padding(.horizontal, 4)
    }

    private var submitButton: some View {
        VStack(spacing: 8) {
            if let notice = viewModel.notice {
                Text(notice).font(.caption).foregroundStyle(.red)
            }
            Button {
                submit()
            } label: {
                Text("Add Expense")
                    .font(.body.weight(.semibold))
                    .foregroundStyle(.white)
                    .frame(maxWidth: .infinity)
                    .padding(18)
                    .background(
                        viewModel.canSubmit ? Theme.accent : Theme.accent.opacity(0.4),
                        in: RoundedRectangle(cornerRadius: 14)
                    )
            }
            .buttonStyle(.plain)
            .disabled(!viewModel.canSubmit)
        }
    }

    private func submit() {
        guard let expense = viewModel.submit(categories: categories, context: modelContext) else { return }
        LiveActivityManager.shared.startConfirmation(
            amount: expense.amount,
            category: expense.categoryName
        )
        dismiss()
    }
}
