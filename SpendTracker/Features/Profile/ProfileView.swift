import SwiftUI

struct ProfileView: View {
    @EnvironmentObject private var auth: AuthViewModel
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ZStack {
                TabbyBackdrop()
                List {
                    Section {
                        VStack(alignment: .leading, spacing: 8) {
                            HStack { TabbyOrbit(size: 28); Text("Your tab").font(.headline).foregroundStyle(Theme.ink) }
                            Text(auth.session?.email ?? "—").font(.subheadline).foregroundStyle(Theme.subtleInk)
                            Text(auth.session?.userId ?? "—").font(.caption2.monospaced()).foregroundStyle(Theme.subtleInk.opacity(0.7)).lineLimit(1)
                        }
                        .padding(.vertical, 8)
                    }
                    .listRowBackground(Theme.surface.opacity(0.86))

                    Section("SPACE") {
                        NavigationLink { ManageCategoriesView() } label: {
                            Label("Categories", systemImage: "square.grid.2x2.fill").foregroundStyle(Theme.ink)
                        }
                    }
                    .listRowBackground(Theme.surface.opacity(0.86))

                    Section {
                        Button(role: .destructive) { Task { await auth.signOut() } } label: {
                            Label("Sign Out", systemImage: "rectangle.portrait.and.arrow.right").foregroundStyle(.red)
                        }
                    }
                    .listRowBackground(Theme.surface.opacity(0.86))
                }
                .scrollContentBackground(.hidden)
            }
            .navigationTitle("Profile")
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("Done") { dismiss() }.foregroundStyle(Theme.accentBright) } }
            .tint(Theme.accent)
        }
        .preferredColorScheme(.dark)
    }
}
