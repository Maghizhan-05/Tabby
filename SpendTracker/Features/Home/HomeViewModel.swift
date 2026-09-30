import Foundation
import SwiftUI

/// Analytics view modes shown in the compact selector.
enum AnalyticsMode: String, CaseIterable, Identifiable {
    case daily = "Daily"
    case weekly = "Weekly"
    case monthly = "Monthly"
    case yearly = "Yearly"
    case categories = "Categories"
    case trends = "Trends"

    var id: String { rawValue }
}

@MainActor
final class HomeViewModel: ObservableObject {
    @Published var selectedMode: AnalyticsMode = .daily
    @Published var showProfile = false
}
