import SwiftUI

struct FriendEditSheetView: View {
    @ObservedObject var viewModel: FriendsViewModel
    let onSave: () -> Void
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ZStack {
                TabbyBackdrop()
                Form {
                    Section("FRIEND") {
                        TextField("Name", text: $viewModel.name)
                            .textInputAutocapitalization(.words)
                            .autocorrectionDisabled()
                    }
                    .listRowBackground(Theme.surface.opacity(0.86))

                    Section("BALANCES") {
                        TextField("They owe you", text: $viewModel.theyOweUsText)
                            .keyboardType(.decimalPad)
                        TextField("You owe them", text: $viewModel.weOweThemText)
                            .keyboardType(.decimalPad)
                    }
                    .listRowBackground(Theme.surface.opacity(0.86))

                    if let notice = viewModel.notice {
                        Section {
                            Text(notice).foregroundStyle(.red)
                        }
                        .listRowBackground(Theme.surface.opacity(0.86))
                    }
                }
                .scrollContentBackground(.hidden)
            }
            .navigationTitle(viewModel.editingFriend == nil ? "Add Friend" : "Edit Friend")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") { onSave() }
                        .disabled(!viewModel.canSave)
                        .foregroundStyle(Theme.accentBright)
                }
            }
            .tint(Theme.accent)
        }
        .preferredColorScheme(.dark)
    }
}
