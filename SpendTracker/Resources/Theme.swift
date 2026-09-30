import SwiftUI

/// Ink / soft-paper design tokens with one accessible violet accent.
enum Theme {
    static let accent = Color(red: 0.44, green: 0.30, blue: 0.85)      // violet
    static let ink = Color(red: 0.10, green: 0.10, blue: 0.12)          // near-black text
    static let paper = Color(red: 0.98, green: 0.97, blue: 0.96)        // soft paper base
    static let subtleInk = Color(red: 0.45, green: 0.45, blue: 0.48)    // secondary text
    static let hairline = Color(red: 0.88, green: 0.87, blue: 0.85)     // dividers

    static let ringColors: [Color] = [
        accent,
        Color(red: 0.35, green: 0.55, blue: 0.90),
        Color(red: 0.30, green: 0.75, blue: 0.65),
        Color(red: 0.90, green: 0.60, blue: 0.30),
        Color(red: 0.85, green: 0.40, blue: 0.55),
        Color(red: 0.55, green: 0.45, blue: 0.80),
        Color(red: 0.50, green: 0.70, blue: 0.40),
        Color(red: 0.60, green: 0.60, blue: 0.62),
    ]
}
