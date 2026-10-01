import SwiftUI
import SwiftData

@main
struct SpendTrackerApp: App {
    @StateObject private var authViewModel = AuthViewModel()
    @State private var showQuickEntry = false

    var body: some Scene {
        WindowGroup {
            RootView(showQuickEntry: $showQuickEntry)
                .environmentObject(authViewModel)
                .modelContainer(SharedModelContainer.shared)
                .onOpenURL { url in
                    handleDeepLink(url)
                }
                .preferredColorScheme(.dark)
        }
    }

    private func handleDeepLink(_ url: URL) {
        guard url.scheme == AppGroupConstants.urlScheme else { return }
        // OAuth callback: spendtracker://auth-callback
        if url.host == "auth-callback" {
            Task { await authViewModel.handleOAuthCallback(url) }
            return
        }
        if url.host == AppGroupConstants.quickEntryHost {
            showQuickEntry = true
        }
    }
}

/// Root routing: shows login when signed out, home when authenticated.
struct RootView: View {
    @EnvironmentObject private var authViewModel: AuthViewModel
    @Binding var showQuickEntry: Bool

    var body: some View {
        Group {
            if authViewModel.isAuthenticated {
                TabView {
                    HomeView(showQuickEntry: $showQuickEntry)
                        .tabItem { Label("Home", systemImage: "house.fill") }
                    FriendsView()
                        .tabItem { Label("Friends", systemImage: "person.2.fill") }
                }
                .tint(Theme.accentBright)
            } else {
                LoginView()
            }
        }
        .animation(.easeInOut, value: authViewModel.isAuthenticated)
        .onAppear {
            QuickEntryLauncher.shared.onRequest = {
                showQuickEntry = true
            }
        }
    }
}
