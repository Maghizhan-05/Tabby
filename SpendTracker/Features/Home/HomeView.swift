import SwiftUI
import SwiftData

/// Split layout: top-half analytics, bottom-half recent entries + quick-entry button.
struct HomeView: View {
    @StateObject private var viewModel = HomeViewModel()
    @Binding var showQuickEntry: Bool

    var body: some View {
        GeometryReader { geo in
            ZStack {
                Theme.paper.ignoresSafeArea()

                VStack(spacing: 0) {
                    // TOP HALF: analytics
                    VStack(spacing: 0) {
                        header
                        AnalyticsView(viewModel: viewModel)
                            .padding(.horizontal, 20)
                        Spacer(minLength: 0)
                    }
                    .frame(height: geo.size.height * 0.5)

                    Rectangle().fill(Theme.hairline).frame(height: 1)

                    // BOTTOM HALF: recent entries
                    VStack(spacing: 0) {
                        HStack {
                            Text("Recent")
                                .font(.headline)
                                .foregroundStyle(Theme.ink)
                            Spacer()
                        }
                        .padding(.horizontal, 20)
                        .padding(.top, 14)

                        RecentEntriesListView()
                    }
                    .frame(height: geo.size.height * 0.5)
                }

                // Floating quick-entry button
                VStack {
                    Spacer()
                    Button {
                        showQuickEntry = true
                    } label: {
                        Image(systemName: "plus")
                            .font(.title2.weight(.bold))
                            .foregroundStyle(.white)
                            .frame(width: 60, height: 60)
                            .background(Theme.accent, in: Circle())
                            .shadow(color: Theme.accent.opacity(0.35), radius: 8, y: 4)
                    }
                    .padding(.bottom, 28)
                }
            }
        }
        .sheet(isPresented: $showQuickEntry) {
            QuickEntrySheetView()
        }
        .sheet(isPresented: $viewModel.showProfile) {
            ProfileView()
        }
        .overlay(alignment: .topTrailing) {
            Button {
                viewModel.showProfile = true
            } label: {
                Image(systemName: "person.circle")
                    .font(.title2)
                    .foregroundStyle(Theme.ink)
                    .padding(.trailing, 20)
                    .padding(.top, 8)
            }
        }
    }

    private var header: some View {
        HStack {
            Text("Tabby")
                .font(.system(size: 28, weight: .bold, design: .rounded))
                .foregroundStyle(Theme.ink)
            Spacer()
        }
        .padding(.horizontal, 20)
        .padding(.top, 12)
        .padding(.bottom, 8)
    }
}
