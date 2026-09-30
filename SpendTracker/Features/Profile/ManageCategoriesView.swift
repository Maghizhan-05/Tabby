import SwiftUI
import SwiftData

struct ManageCategoriesView: View {
    @Environment(\.modelContext) private var modelContext
    @Query(sort: \Category.sortOrder) private var categories: [Category]
    @StateObject private var viewModel = ProfileViewModel()

    var body: some View {
        List {
            Section("Add Category") {
                HStack {
                    TextField("New category name", text: $viewModel.newCategoryName)
                        .autocorrectionDisabled()
                    Button {
                        viewModel.addCategory(context: modelContext, existing: categories)
                    } label: {
                        Image(systemName: "plus.circle.fill")
                            .foregroundStyle(Theme.accent)
                    }
                    .disabled(viewModel.newCategoryName.trimmingCharacters(in: .whitespaces).isEmpty)
                }
                if let notice = viewModel.notice {
                    Text(notice).font(.caption).foregroundStyle(.red)
                }
            }

            Section("Categories") {
                ForEach(categories) { category in
                    HStack {
                        Text(category.name)
                        Spacer()
                        if category.isDefault {
                            Text("Default")
                                .font(.caption2)
                                .foregroundStyle(Theme.subtleInk)
                        }
                    }
                }
                .onDelete(perform: deleteCategories)
            }
        }
        .navigationTitle("Categories")
        .tint(Theme.accent)
    }

    private func deleteCategories(at offsets: IndexSet) {
        for index in offsets {
            let category = categories[index]
            viewModel.deleteCategory(category, context: modelContext)
        }
    }
}
