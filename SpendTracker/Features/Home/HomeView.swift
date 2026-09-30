import SwiftUI
import SwiftData

/// A continuous home canvas: one analytics surface flows into recent spending.
struct HomeView: View {
    @StateObject private var viewModel = HomeViewModel()
    @Binding var showQuickEntry: Bool

    var body: some View {
        GeometryReader { geo in
            ZStack {
                TabbyBackdrop()

                VStack(spacing: 14) {
                    header

                    VStack(spacing: 16) {
                        AnalyticsView(viewModel: viewModel)
                    }
                    .padding(18)
                    .frame(height: geo.size.height * 0.49)
                    .background(Theme.surface.opacity(0.82), in: Theme.cardShape)
                    .overlay(Theme.cardShape.stroke(Theme.hairline))

                    VStack(alignment: .leading, spacing: 8) {
                        HStack {
                            Text("Recent activity")
                                .font(.system(.headline, design: .rounded, weight: .semibold))
                                .foregroundStyle(Theme.ink)
                            Spacer()
                            Text("LIVE")
                                .font(.caption2.weight(.bold))
                                .tracking(1)
                                .foregroundStyle(Theme.accentBright)
                        }
                        RecentEntriesListView()
                    }
                    .padding(.top, 4)
                    .frame(maxHeight: .infinity, alignment: .top)
                }
                .padding(.horizontal, 16)
                .padding(.top, 8)

                VStack {
                    Spacer()
                    HStack {
                        Spacer()
                        Button {
                            showQuickEntry = true
                        } label: {
                            HStack(spacing: 10) {
                                Image(systemName: "plus").font(.body.weight(.bold))
                                Text("Add spend").font(.subheadline.weight(.bold))
                            }
                            .foregroundStyle(Theme.paper)
                            .padding(.horizontal, 18)
                            .padding(.vertical, 15)
                            .background(Theme.accent, in: Capsule())
                            .shadow(color: Theme.accentGlow, radius: 16, y: 6)
                        }
                        .buttonStyle(.plain)
                    }
                    .padding(.trailing, 22)
                    .padding(.bottom, 22)
                }
            }
        }
        .preferredColorScheme(.dark)
        .sheet(isPresented: $showQuickEntry) { QuickEntrySheetView() }
        .sheet(isPresented: $viewModel.showProfile) { ProfileView() }
    }

    private var header: some View {
        HStack(spacing: 10) {
            TabbyOrbit(size: 26, lineWidth: 2.5)
            Text("Tabby")
                .font(.system(size: 25, weight: .bold, design: .rounded))
                .foregroundStyle(Theme.ink)
            Spacer()
            Button { viewModel.showProfile = true } label: {
                Image(systemName: "person.crop.circle")
                    .font(.title2)
                    .foregroundStyle(Theme.subtleInk)
                    .padding(5)
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Profile")
        }
        .padding(.horizontal, 4)
    }
}
