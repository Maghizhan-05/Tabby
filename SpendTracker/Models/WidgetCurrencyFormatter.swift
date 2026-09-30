import Foundation

/// Short, stable rupee labels sized for WidgetKit rings and rows.
enum WidgetCurrencyFormatter {
    private static let rupee = "₹"
    private static let millionThreshold = 999_950.0
    private static let maximumDisplayedMillions = 999.9

    static func string(_ value: Double) -> String {
        guard value.isFinite else { return "₹0" }

        let sign = value < 0 ? "-" : ""
        let magnitude = abs(value)

        if magnitude < 10_000 {
            let formatter = NumberFormatter()
            formatter.locale = Locale(identifier: "en_IN")
            formatter.numberStyle = .decimal
            formatter.minimumFractionDigits = 0
            formatter.maximumFractionDigits = 2
            formatter.usesGroupingSeparator = true
            let number = formatter.string(from: NSNumber(value: magnitude)) ?? "0"
            return "\(sign)\(rupee)\(number)"
        }

        if magnitude < millionThreshold {
            return "\(sign)\(rupee)\(abbreviated(magnitude / 1_000))K"
        }

        let millions = magnitude / 1_000_000
        if millions > maximumDisplayedMillions {
            return "\(sign)\(rupee)999.9M+"
        }
        return "\(sign)\(rupee)\(abbreviated(millions))M"
    }

    private static func abbreviated(_ value: Double) -> String {
        let rounded = (value * 10).rounded() / 10
        if rounded == rounded.rounded() {
            return String(format: "%.0f", rounded)
        }
        return String(format: "%.1f", rounded)
    }
}
