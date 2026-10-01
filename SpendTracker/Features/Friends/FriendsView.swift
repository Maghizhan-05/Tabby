import SwiftUI
import SwiftData

struct FriendsView: View {
    @Environment(\.modelContext) private var modelContext
    @EnvironmentObject private var auth: AuthViewModel
    @Query(sort: \Friend.name) private var friends: [Friend]
    @StateObject private var viewModel = FriendsViewModel()

    private var ownerId: String? { auth.session?.userId }

    private var activeFriends: [Friend] {
        FriendsViewModel.visibleFriends(friends, ownerId: ownerId)
    }

    var body: some View {
        NavigationStack {
            ZStack {
                TabbyBackdrop()
                Group {
                    if activeFriends.isEmpty {
                        ContentUnavailableView(
                            "No friends yet",
                            systemImage: "person.2",
                            description: Text("Track what each friend owes, or what you owe them.")
                        )
                        .foregroundStyle(Theme.subtleInk)
                    } else {
                        List {
                            Section {
                                ForEach(activeFriends) { friend in
                                    Button { viewModel.beginEditing(friend) } label: {
                                        FriendRow(friend: friend)
                                    }
                                    .buttonStyle(.plain)
                                    .listRowBackground(Theme.surface.opacity(0.86))
                                }
                                .onDelete(perform: deleteFriends)
                            } header: {
                                FriendTableHeader()
                            } footer: {
                                AggregateNetFooter(total: FriendsViewModel.aggregateNet(of: activeFriends))
                            }
                        }
                        .scrollContentBackground(.hidden)
                    }
                }
            }
            .navigationTitle("Friends")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button { viewModel.beginAdding() } label: {
                        Image(systemName: "plus")
                    }
                    .accessibilityLabel("Add friend")
                }
            }
            .tint(Theme.accent)
        }
        .sheet(isPresented: $viewModel.isPresentingEditor) {
            FriendEditSheetView(viewModel: viewModel) {
                if viewModel.save(context: modelContext, ownerId: ownerId) {
                    Task { await syncFriends() }
                }
            }
        }
        .task { await syncFriends() }
        .onChange(of: ownerId) { _, _ in
            viewModel.isPresentingEditor = false
            viewModel.editingFriend = nil
            Task { await syncFriends() }
        }
        .preferredColorScheme(.dark)
    }

    private func deleteFriends(at offsets: IndexSet) {
        var didDelete = false
        for index in offsets {
            if viewModel.delete(activeFriends[index], context: modelContext, ownerId: ownerId) {
                didDelete = true
            }
        }
        if didDelete { Task { await syncFriends() } }
    }

    private func syncFriends() async {
        guard let ownerId else { return }
        let engine = SyncEngine(
            modelContext: modelContext,
            expenseRepository: SupabaseExpenseRepository(),
            categoryRepository: SupabaseCategoryRepository(),
            friendRepository: SupabaseFriendRepository()
        )
        await engine.pushUnsyncedFriends(ownerId: ownerId)
    }
}

private struct FriendRow: View {
    let friend: Friend

    var body: some View {
        HStack(spacing: 8) {
            Text(friend.name)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(Theme.ink)
                .lineLimit(1)
                .frame(maxWidth: .infinity, alignment: .leading)
            amountCell(friend.theyOweUs, color: Theme.accentBright)
            amountCell(friend.weOweThem, color: Theme.subtleInk)
            amountCell(friend.netBalance, color: friend.netBalance >= 0 ? Theme.accentBright : .red.opacity(0.82))
        }
        .padding(.vertical, 6)
    }

    private func amountCell(_ value: Decimal, color: Color) -> some View {
        Text(currency(value))
            .font(.caption.weight(.semibold).monospacedDigit())
            .foregroundStyle(color)
            .lineLimit(1)
            .minimumScaleFactor(0.65)
            .frame(width: 70, alignment: .trailing)
    }

    private func currency(_ value: Decimal) -> String {
        WidgetCurrencyFormatter.string(NSDecimalNumber(decimal: value).doubleValue)
    }
}

private struct FriendTableHeader: View {
    var body: some View {
        HStack(spacing: 8) {
            Text("FRIEND").frame(maxWidth: .infinity, alignment: .leading)
            Text("THEY OWE").frame(width: 70, alignment: .trailing)
            Text("YOU OWE").frame(width: 70, alignment: .trailing)
            Text("NET").frame(width: 70, alignment: .trailing)
        }
        .font(.caption2.weight(.bold))
        .foregroundStyle(Theme.subtleInk)
        .textCase(nil)
    }
}

private struct AggregateNetFooter: View {
    let total: Decimal

    var body: some View {
        HStack {
            Text("Aggregate net")
            Spacer()
            Text(WidgetCurrencyFormatter.string(NSDecimalNumber(decimal: total).doubleValue))
                .fontWeight(.bold)
                .monospacedDigit()
                .foregroundStyle(total >= 0 ? Theme.accentBright : .red.opacity(0.82))
        }
        .font(.subheadline)
        .textCase(nil)
    }
}
