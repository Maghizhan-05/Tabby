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
                TabbyBackdrop()
                ScrollView {
                    VStack(spacing: 24) {
                        HStack {
                            VStack(alignment: .leading, spacing: 3) {
                                Text("NEW TAB").font(.caption.weight(.bold)).tracking(1.4).foregroundStyle(Theme.accentBright)
                                Text("Log a spend").font(.title2.weight(.bold)).foregroundStyle(Theme.ink)
                            }
                            Spacer()
                            TabbyOrbit(size: 34)
                        }
                        amountField
                        categoryField
                        noteField
                        datePicker
                    }
                    .padding(24)
                    .padding(.bottom, 12)
                }
                .scrollDismissesKeyboard(.interactively)
            }
            .safeAreaInset(edge: .bottom, spacing: 0) {
                submitButton
                    .padding(.horizontal, 24)
                    .padding(.top, 12)
                    .padding(.bottom, 16)
                    .background(.ultraThinMaterial)
            }
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) { Button("Cancel") { dismiss() }.foregroundStyle(Theme.subtleInk) }
            }
            .tint(Theme.accent)
        }
        .preferredColorScheme(.dark)
        .onAppear { amountFocused = true }
        .sheet(isPresented: $showCategoryPicker) {
            NavigationStack {
                ZStack {
                    TabbyBackdrop()
                    CategoryPickerView(query: $viewModel.categoryQuery) { name in
                        viewModel.categoryQuery = name
                        showCategoryPicker = false
                    }
                    .searchable(text: $viewModel.categoryQuery, prompt: "Search or add category")
                    .navigationTitle("Category")
                    .navigationBarTitleDisplayMode(.inline)
                }
            }
            .preferredColorScheme(.dark)
            .presentationDetents([.medium, .large])
        }
    }

    private var amountField: some View {
        VStack(spacing: 7) {
            Text("AMOUNT").font(.caption.weight(.bold)).foregroundStyle(Theme.subtleInk).tracking(1.3)
            TextField("0", text: $viewModel.amountText)
                .font(.system(size: 64, weight: .bold, design: .rounded))
                .foregroundStyle(Theme.ink)
                .multilineTextAlignment(.center)
                .keyboardType(.decimalPad)
                .focused($amountFocused)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 16)
        .background(Theme.surface.opacity(0.72), in: Theme.cardShape)
        .overlay(Theme.cardShape.stroke(amountFocused ? Theme.accent.opacity(0.62) : Theme.hairline, lineWidth: amountFocused ? 1.4 : 1))
        .shadow(color: amountFocused ? Theme.accentGlow : .clear, radius: 14)
    }

    private var categoryField: some View {
        Button { showCategoryPicker = true } label: {
            HStack(spacing: 12) {
                TabbyOrbit(size: 24, lineWidth: 2)
                Text(viewModel.categoryQuery.isEmpty ? "Choose category" : viewModel.categoryQuery)
                    .font(.body.weight(.medium))
                    .foregroundStyle(viewModel.categoryQuery.isEmpty ? Theme.subtleInk : Theme.ink)
                Spacer()
                Image(systemName: "chevron.right").font(.caption.weight(.bold)).foregroundStyle(Theme.accentBright)
            }
            .padding(16)
            .background(Theme.surface.opacity(0.88), in: Theme.controlShape)
            .overlay(Theme.controlShape.stroke(Theme.hairline))
        }
        .buttonStyle(.plain)
    }

    private var noteField: some View {
        VStack(alignment: .leading, spacing: 6) {
            TextField("Note (optional)", text: $viewModel.noteText, axis: .vertical)
                .lineLimit(1...2)
                .font(.subheadline)
                .foregroundStyle(Theme.ink)
                .padding(16)
                .background(Theme.surface.opacity(0.88), in: Theme.controlShape)
                .overlay(Theme.controlShape.stroke(viewModel.isNoteValid ? Theme.hairline : .red.opacity(0.7)))
            Text("\(viewModel.noteText.count)/\(Expense.maximumNoteLength)")
                .font(.caption2.monospacedDigit())
                .foregroundStyle(viewModel.isNoteValid ? Theme.subtleInk : .red)
                .frame(maxWidth: .infinity, alignment: .trailing)
        }
    }

    private var datePicker: some View {
        DatePicker("When", selection: $viewModel.selectedDate, displayedComponents: [.date, .hourAndMinute])
            .font(.subheadline.weight(.medium))
            .foregroundStyle(Theme.ink)
            .datePickerStyle(.compact)
            .padding(16)
            .background(Theme.surface.opacity(0.88), in: Theme.controlShape)
            .overlay(Theme.controlShape.stroke(Theme.hairline))
    }

    private var submitButton: some View {
        VStack(spacing: 8) {
            if let notice = viewModel.notice { Text(notice).font(.caption).foregroundStyle(.red) }
            Button { submit() } label: {
                HStack { Text("Lock it in"); Spacer(); Image(systemName: "arrow.up.right").font(.subheadline.weight(.bold)) }
                    .font(.body.weight(.bold))
                    .foregroundStyle(Theme.paper)
                    .padding(.horizontal, 20)
                    .padding(.vertical, 18)
                    .background(viewModel.canSubmit ? Theme.accent : Theme.accent.opacity(0.32), in: Theme.controlShape)
                    .shadow(color: viewModel.canSubmit ? Theme.accentGlow : .clear, radius: 15, y: 6)
            }
            .buttonStyle(.plain)
            .disabled(!viewModel.canSubmit)
        }
    }


    private func submit() {
        guard let expense = viewModel.submit(categories: categories, context: modelContext) else { return }
        LiveActivityManager.shared.startConfirmation(amount: expense.amount, category: expense.categoryName)
        dismiss()
    }
}
