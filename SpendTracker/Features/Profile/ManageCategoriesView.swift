import SwiftUI
import SwiftData

struct ManageCategoriesView: View {
    @Environment(\.modelContext) private var modelContext
    @Environment(\.scenePhase) private var scenePhase
    @EnvironmentObject private var auth: AuthViewModel
    @Query(sort: \Category.sortOrder) private var allCategories: [Category]
    @StateObject private var viewModel = ProfileViewModel()

    private var ownerId: String? { auth.session?.userId }

    /// Defaults plus this account's own custom categories.
    private var categories: [Category] {
        ProfileViewModel.visibleCategories(allCategories, ownerId: ownerId)
    }

    var body: some View {
        ZStack {
            TabbyBackdrop()
            List {
                Section("NEW CATEGORY") {
                    HStack(spacing: 12) {
                        TextField("Name it", text: $viewModel.newCategoryName)
                            .foregroundStyle(Theme.ink)
                            .autocorrectionDisabled()
                        Button {
                            viewModel.addCategory(
                                context: modelContext, existing: allCategories, ownerId: ownerId
                            )
                        } label: {
                            Image(systemName: "plus").font(.body.weight(.bold)).foregroundStyle(Theme.paper)
                                .padding(8).background(Theme.accent, in: Circle())
                        }
                        .disabled(viewModel.newCategoryName.trimmingCharacters(in: .whitespaces).isEmpty)
                    }
                    if let notice = viewModel.notice { Text(notice).font(.caption).foregroundStyle(.red) }
                }
                .listRowBackground(Theme.surface.opacity(0.86))

                Section("YOUR CATEGORIES") {
                    ForEach(categories) { category in
                        HStack(spacing: 12) {
                            Circle().fill(category.isDefault ? Theme.subtleInk.opacity(0.45) : Theme.accent).frame(width: 8, height: 8)
                            Text(category.name).foregroundStyle(Theme.ink)
                            Spacer()
                            if category.isDefault { Text("DEFAULT").font(.caption2.weight(.bold)).tracking(0.8).foregroundStyle(Theme.subtleInk) }
                        }
                    }
                    .onDelete(perform: deleteCategories)
                    .listRowBackground(Theme.surface.opacity(0.86))
                }
            }
            .scrollContentBackground(.hidden)
        }
        .navigationTitle("Categories")
        .tint(Theme.accent)
        // Custom categories sync across the account's devices: reconcile when
        // the screen appears, on foreground, and when the account changes.
        .task { await syncCategories() }
        .onChange(of: scenePhase) { _, phase in
            guard phase == .active else { return }
            Task { await syncCategories() }
        }
        .onChange(of: ownerId) { _, _ in
            Task { await syncCategories() }
        }
    }

    private func deleteCategories(at offsets: IndexSet) {
        let visible = categories
        for index in offsets {
            guard visible.indices.contains(index) else { continue }
            viewModel.deleteCategory(visible[index], context: modelContext, ownerId: ownerId)
        }
        Task { await syncCategories() }
    }

    private func syncCategories() async {
        guard let ownerId else { return }
        await SyncEngine(
            modelContext: modelContext,
            expenseRepository: SupabaseExpenseRepository(),
            categoryRepository: SupabaseCategoryRepository()
        ).syncCategories(ownerId: ownerId)
    }
}
