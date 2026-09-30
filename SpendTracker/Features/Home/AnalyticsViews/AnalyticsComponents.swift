import SwiftUI

/// Dominant amount typography used across analytics headers.
struct AmountHeadline: View {
    let title: String
    let amount: Decimal

    var body: some View {
        VStack(spacing: 2) {
            Text(title.uppercased())
                .font(.caption.weight(.semibold))
                .foregroundStyle(Theme.subtleInk)
                .tracking(1)
            Text(CurrencyFormat.string(amount))
                .font(.system(size: 40, weight: .bold, design: .rounded))
                .foregroundStyle(Theme.ink)
                .minimumScaleFactor(0.5)
                .lineLimit(1)
        }
        .frame(maxWidth: .infinity)
    }
}

struct EmptyAnalytics: View {
    var body: some View {
        VStack(spacing: 6) {
            TabbyOrbit(size: 34, lineWidth: 2)
                .opacity(0.8)
            Text("No spending yet")
                .font(.footnote)
                .foregroundStyle(Theme.subtleInk)
        }
        .frame(maxWidth: .infinity, minHeight: 140)
    }
}

enum CurrencyFormat {
    static func string(_ amount: Decimal) -> String {
        let formatter = NumberFormatter()
        formatter.numberStyle = .currency
        formatter.locale = Locale.current
        return formatter.string(from: NSDecimalNumber(decimal: amount)) ?? "\(amount)"
    }
}
