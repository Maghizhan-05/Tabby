import SwiftUI
import SwiftData
import WidgetKit

@main
struct SpendTrackerApp: App {
    @StateObject private var authViewModel = AuthViewModel()
    @State private var showQuickEntry = false

    /// Resolved by touching `SharedModelContainer.shared`, which records any
    /// App Group misconfiguration instead of silently using a private store.
    private let container = SharedModelContainer.shared

    var body: some Scene {
        WindowGroup {
            Group {
                if let error = SharedModelContainer.configurationError {
                    // Fail loudly in Release too: a silent per-process fallback
                    // would desync the app and widget with no crashlog.
                    StorageConfigurationErrorView(error: error)
                } else {
                    RootView(showQuickEntry: $showQuickEntry)
                        .environmentObject(authViewModel)
                        .onOpenURL { url in
                            handleDeepLink(url)
                        }
                }
            }
            .modelContainer(container)
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

/// Blocking screen shown when the shared store is not backed by the App Group
/// container. Replaces the previous debug-only `assertionFailure`, which left
/// Release builds silently reading a different database than the widget.
struct StorageConfigurationErrorView: View {
    let error: SharedModelContainer.ConfigurationError

    var body: some View {
        ZStack {
            TabbyBackdrop()
            VStack(spacing: 18) {
                Image(systemName: "externaldrive.badge.exclamationmark")
                    .font(.system(size: 44, weight: .semibold))
                    .foregroundStyle(Theme.accent)
                Text("STORAGE UNAVAILABLE")
                    .font(.caption.weight(.bold))
                    .tracking(1.2)
                    .foregroundStyle(Theme.subtleInk)
                Text(error.message)
                    .font(.callout)
                    .multilineTextAlignment(.center)
                    .foregroundStyle(Theme.ink)
                #if DEBUG
                Text(error.developerHint)
                    .font(.caption2)
                    .multilineTextAlignment(.center)
                    .foregroundStyle(Theme.subtleInk)
                #endif
            }
            .padding(28)
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
            // Keep the home-screen widget pinned to the signed-in account so it
            // never renders a previous user's spending.
            WidgetDataProvider.publishActiveOwner(authViewModel.session?.userId)
            WidgetCenter.shared.reloadAllTimelines()
        }
        .onChange(of: authViewModel.session?.userId) { _, newOwner in
            WidgetDataProvider.publishActiveOwner(newOwner)
            WidgetCenter.shared.reloadAllTimelines()
        }
    }
}
