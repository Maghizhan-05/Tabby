import SwiftUI
import Charts

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

/// Maps the angle-selection value provided by Charts to a donut segment.
enum DonutSelection {
    enum Intent {
        case toggle(String)
        case clear
    }

    static func index(for angle: Double, values: [Double]) -> Int? {
        guard angle >= 0, !values.isEmpty else { return nil }

        var cumulative = 0.0
        for (index, value) in values.enumerated() where value > 0 {
            cumulative += value
            if angle <= cumulative {
                return index
            }
        }
        return nil
    }

    static func category(after intent: Intent, current: String?) -> String? {
        switch intent {
        case let .toggle(category):
            return current == category ? nil : category
        case .clear:
            return nil
        }
    }

    static func isLatest(generation: UInt, currentGeneration: UInt) -> Bool {
        generation == currentGeneration
    }
}

/// Compact, in-bounds context for a selected donut segment.
struct SegmentSelectionBubble: View {
    let item: CategoryTotal
    let percentage: Double
    let dismiss: () -> Void

    var body: some View {
        HStack(spacing: 7) {
            Circle()
                .fill(item.color)
                .frame(width: 9, height: 9)
                .accessibilityHidden(true)

            VStack(alignment: .leading, spacing: 1) {
                Text(item.category)
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(Theme.ink)
                    .lineLimit(1)
                Text("\(CurrencyFormat.string(item.total)) · \(percentage, format: .percent.precision(.fractionLength(0)))")
                    .font(.caption2)
                    .foregroundStyle(Theme.subtleInk)
                    .lineLimit(1)
            }
            .accessibilityElement(children: .combine)
            .accessibilityLabel("\(item.category), \(CurrencyFormat.string(item.total)), \(percentage, format: .percent.precision(.fractionLength(0))) of total")

            Button(action: dismiss) {
                Image(systemName: "xmark")
                    .font(.caption2.weight(.bold))
                    .foregroundStyle(Theme.subtleInk)
                    .frame(width: 18, height: 18)
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Dismiss selection")
        }
        .padding(.horizontal, 9)
        .padding(.vertical, 7)
        .background(Theme.elevatedSurface, in: Theme.controlShape)
        .overlay(Theme.controlShape.stroke(Theme.hairline))
        .accessibilityElement(children: .contain)
    }
}

/// Native Swift Charts donut selection with a compact, clamped segment callout.
struct SelectableDonutChart: View {
    let totals: [CategoryTotal]
    @Binding var selectedCategory: String?
    let innerRadius: Double

    @State private var selectedAngle: Double?
    @State private var selectionGeneration: UInt = 0

    private var selectedItem: CategoryTotal? {
        totals.first { $0.category == selectedCategory }
    }

    private var total: Decimal {
        totals.reduce(0) { $0 + $1.total }
    }

    var body: some View {
        GeometryReader { geometry in
            Chart(totals) { item in
                SectorMark(
                    angle: .value("Total", NSDecimalNumber(decimal: item.total).doubleValue),
                    innerRadius: .ratio(innerRadius),
                    angularInset: 1.5
                )
                .foregroundStyle(item.color)
                .cornerRadius(3)
                .opacity(selectedCategory == nil || selectedCategory == item.category ? 1 : 0.35)
                .accessibilityLabel(item.category)
                .accessibilityValue("\(CurrencyFormat.string(item.total)), \(percentage(for: item), format: .percent.precision(.fractionLength(0))) of total")
            }
            .chartAngleSelection(value: $selectedAngle)
            .onChange(of: selectedAngle) { _, angle in
                guard let angle,
                      let index = DonutSelection.index(
                        for: angle,
                        values: totals.map { NSDecimalNumber(decimal: $0.total).doubleValue }
                      ) else {
                    scheduleSelectionChange(.clear)
                    return
                }

                scheduleSelectionChange(.toggle(totals[index].category))
            }
            .simultaneousGesture(
                SpatialTapGesture().onEnded { tap in
                    if !isInsideRing(tap.location, in: geometry.size) {
                        scheduleSelectionChange(.clear)
                    }
                }
            )
            .overlay {
                ZStack {
                    if let item = selectedItem {
                        SegmentSelectionBubble(item: item, percentage: percentage(for: item), dismiss: {
                            scheduleSelectionChange(.clear)
                        })
                            .frame(maxWidth: 124)
                            .position(bubblePosition(for: item, in: geometry.size))
                            .transition(.opacity.combined(with: .scale(scale: 0.94)))
                    }
                }
                .animation(.easeInOut(duration: 0.18), value: selectedCategory)
            }
        }
    }

    private func percentage(for item: CategoryTotal) -> Double {
        guard total > 0 else { return 0 }
        return NSDecimalNumber(decimal: item.total).doubleValue / NSDecimalNumber(decimal: total).doubleValue
    }

    private func bubblePosition(for item: CategoryTotal, in size: CGSize) -> CGPoint {
        let values = totals.map { NSDecimalNumber(decimal: $0.total).doubleValue }
        guard let index = totals.firstIndex(where: { $0.category == item.category }) else {
            return CGPoint(x: size.width / 2, y: size.height / 2)
        }

        let cumulativeBefore = values.prefix(index).reduce(0, +)
        let midpoint = cumulativeBefore + (values[index] / 2)
        let fraction = midpoint / values.reduce(0, +)
        let angle = (fraction * 2 * .pi) - (.pi / 2)
        let radius = min(size.width, size.height) * 0.39
        let rawX = (size.width / 2) + cos(angle) * radius
        let rawY = (size.height / 2) + sin(angle) * radius
        let horizontalInset = min(62.0, size.width / 2)
        let verticalInset = min(24.0, size.height / 2)

        return CGPoint(
            x: min(max(rawX, horizontalInset), size.width - horizontalInset),
            y: min(max(rawY, verticalInset), size.height - verticalInset)
        )
    }

    private func isInsideRing(_ location: CGPoint, in size: CGSize) -> Bool {
        let center = CGPoint(x: size.width / 2, y: size.height / 2)
        let distance = hypot(location.x - center.x, location.y - center.y)
        let outerRadius = min(size.width, size.height) / 2
        return distance >= outerRadius * innerRadius && distance <= outerRadius
    }

    private func scheduleSelectionChange(_ intent: DonutSelection.Intent) {
        selectionGeneration &+= 1
        let generation = selectionGeneration

        Task { @MainActor in
            await Task.yield()
            guard DonutSelection.isLatest(
                generation: generation,
                currentGeneration: selectionGeneration
            ) else {
                return
            }

            selectedCategory = DonutSelection.category(after: intent, current: selectedCategory)
            if case .clear = intent {
                selectedAngle = nil
            }
        }
    }
}
