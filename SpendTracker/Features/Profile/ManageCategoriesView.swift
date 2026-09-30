import SwiftUI
import SwiftData

struct ManageCategoriesView: View {
    @Environment(\.modelContext) private var modelContext
    @Query(sort: \Category.sortOrder) private var categories: [Category]
    @StateObject private var viewModel = ProfileViewModel()

    var body: some View {
        ZStack {
            TabbyBackdrop()
            List {
                Section("NEW CATEGORY") {
                    HStack(spacing: 12) {
                        TextField("Name it", text: $viewModel.newCategoryName)
                            .foregroundStyle(Theme.ink)
                            .autocorrectionDisabled()
                        Button { viewModel.addCategory(context: modelContext, existing: categories) } label: {
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
    }

    private func deleteCategories(at offsets: IndexSet) {
        for index in offsets { viewModel.deleteCategory(categories[index], context: modelContext) }
    }
}
