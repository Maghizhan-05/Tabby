import SwiftUI
import SwiftData

/// A searchable category list with an "Add new category" affordance at the bottom.
struct CategoryPickerView: View {
    @Query(sort: \Category.sortOrder) private var categories: [Category]
    @Binding var query: String
    var onSelect: (String) -> Void

    private var filtered: [Category] {
        let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return categories }
        return categories.filter { $0.name.range(of: trimmed, options: .caseInsensitive) != nil }
    }

    private var isNew: Bool {
        let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return false }
        return !categories.contains { $0.name.compare(trimmed, options: .caseInsensitive) == .orderedSame }
    }

    var body: some View {
        List {
            ForEach(filtered) { category in
                Button { onSelect(category.name) } label: {
                    HStack(spacing: 12) {
                        Circle().fill(Theme.accent.opacity(0.20)).frame(width: 28, height: 28)
                            .overlay(Circle().fill(Theme.accent).frame(width: 6, height: 6))
                        Text(category.name).foregroundStyle(Theme.ink)
                        Spacer()
                        if category.name.compare(query, options: .caseInsensitive) == .orderedSame {
                            Image(systemName: "checkmark").foregroundStyle(Theme.accentBright)
                        }
                    }
                }
                .listRowBackground(Color.clear)
                .listRowSeparatorTint(Theme.hairline)
            }

            if isNew {
                Button { onSelect(query.trimmingCharacters(in: .whitespacesAndNewlines)) } label: {
                    Label("Add \"\(query.trimmingCharacters(in: .whitespacesAndNewlines))\"", systemImage: "plus.circle.fill")
                        .font(.body.weight(.semibold))
                        .foregroundStyle(Theme.accentBright)
                }
                .listRowBackground(Theme.accent.opacity(0.10))
            }
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
    }
}
