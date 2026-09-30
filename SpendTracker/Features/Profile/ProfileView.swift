import SwiftUI

struct ProfileView: View {
    @EnvironmentObject private var auth: AuthViewModel
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                Section("Account") {
                    LabeledContent("Email", value: auth.session?.email ?? "—")
                    LabeledContent("User ID", value: auth.session?.userId ?? "—")
                }

                Section("Categories") {
                    NavigationLink {
                        ManageCategoriesView()
                    } label: {
                        Label("Manage Categories", systemImage: "square.grid.2x2")
                    }
                }

                Section {
                    Button(role: .destructive) {
                        Task { await auth.signOut() }
                    } label: {
                        Text("Sign Out")
                    }
                }
            }
            .navigationTitle("Profile")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Done") { dismiss() }
                }
            }
            .tint(Theme.accent)
        }
    }
}
