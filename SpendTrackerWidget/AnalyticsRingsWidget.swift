import WidgetKit
import SwiftUI

struct AnalyticsEntry: TimelineEntry {
    let date: Date
    let configuration: AnalyticsModeIntent
    let snapshot: WidgetDataProvider.Snapshot
}

struct AnalyticsProvider: AppIntentTimelineProvider {
    func placeholder(in context: Context) -> AnalyticsEntry {
        AnalyticsEntry(
            date: Date(),
            configuration: AnalyticsModeIntent(),
            snapshot: WidgetDataProvider.Snapshot(
                mode: .daily,
                title: "Today",
                total: 42.0,
                slices: [
                    .init(category: "Food", total: 20),
                    .init(category: "Transport", total: 12),
                    .init(category: "Other", total: 10),
                ]
            )
        )
    }

    func snapshot(for configuration: AnalyticsModeIntent, in context: Context) async -> AnalyticsEntry {
        AnalyticsEntry(
            date: Date(),
            configuration: configuration,
            snapshot: WidgetDataProvider.currentSnapshot(for: configuration.mode)
        )
    }

    func timeline(for configuration: AnalyticsModeIntent, in context: Context) async -> Timeline<AnalyticsEntry> {
        let now = Date()
        let entry = AnalyticsEntry(
            date: now,
            configuration: configuration,
            snapshot: WidgetDataProvider.currentSnapshot(for: configuration.mode)
        )
        let next = Calendar.current.date(byAdding: .minute, value: 30, to: now) ?? now
        return Timeline(entries: [entry], policy: .after(next))
    }
}

private let ringPalette: [Color] = [
    Color(red: 1.0, green: 0.84, blue: 0.43),
    Color(red: 0.90, green: 0.66, blue: 0.22),
    Color(red: 0.97, green: 0.50, blue: 0.20),
    Color(red: 0.67, green: 0.45, blue: 0.96),
    Color(red: 0.25, green: 0.70, blue: 0.72),
    Color(red: 0.96, green: 0.34, blue: 0.46),
]

struct AnalyticsRingsWidgetView: View {
    var entry: AnalyticsEntry
    @Environment(\.widgetFamily) private var family

    private var snapshot: WidgetDataProvider.Snapshot { entry.snapshot }
    private var total: Double { max(snapshot.slices.reduce(0) { $0 + $1.total }, 0.0001) }
    private var hasRingData: Bool { !snapshot.slices.isEmpty }
    private var hasTrendData: Bool { snapshot.trendTotals.contains { $0.total > 0 } }

    var body: some View {
        Group {
            if snapshot.mode == .trends {
                trends
            } else {
                rings
            }
        }
        .widgetURL(URL(string: "spendtracker://quick-entry"))
    }

    @ViewBuilder
    private var rings: some View {
        switch family {
        case .systemSmall:
            if hasRingData {
                ring.padding(12)
            } else {
                emptyState.padding(12)
            }
        default:
            ringMedium
        }
    }

    private var emptyState: some View {
        VStack(spacing: 4) {
            Text(snapshot.title)
                .font(.caption2)
                .foregroundStyle(.white.opacity(0.58))
            Text("No spending yet")
                .font(.caption.weight(.semibold))
                .foregroundStyle(.white.opacity(0.82))
                .multilineTextAlignment(.center)
                .minimumScaleFactor(0.7)
        }
    }

    private var ring: some View {
        ZStack {
            ForEach(Array(snapshot.slices.enumerated()), id: \.element.id) { index, slice in
                let start = startFraction(upTo: index)
                let end = start + slice.total / total
                Circle()
                    .trim(from: start, to: end)
                    .stroke(ringPalette[index % ringPalette.count],
                            style: StrokeStyle(lineWidth: 12, lineCap: .butt))
                    .rotationEffect(.degrees(-90))
            }
            VStack(spacing: 0) {
                Text(snapshot.title).font(.caption2).foregroundStyle(.white.opacity(0.58))
                Text(currency(snapshot.total))
                    .font(.headline.weight(.bold))
                    .monospacedDigit()
                    .foregroundStyle(.white)
                    .minimumScaleFactor(0.62)
                    .lineLimit(1)
                    .layoutPriority(1)
            }
        }
    }

    private var ringMedium: some View {
        HStack(spacing: 16) {
            Group {
                if hasRingData {
                    ring
                } else {
                    emptyState
                }
            }
            .frame(width: 90, height: 90)
            VStack(alignment: .leading, spacing: 4) {
                ForEach(Array(snapshot.slices.prefix(4).enumerated()), id: \.element.id) { index, slice in
                    HStack(spacing: 6) {
                        Circle().fill(ringPalette[index % ringPalette.count]).frame(width: 7, height: 7)
                        Text(slice.category).font(.caption2).foregroundStyle(.white.opacity(0.78))
                        Spacer()
                        Text(currency(slice.total)).font(.caption2.weight(.medium).monospacedDigit()).foregroundStyle(.white).lineLimit(1).minimumScaleFactor(0.7)
                    }
                }
                if snapshot.slices.isEmpty {
                    Text("No spending for this period").font(.caption2).foregroundStyle(.secondary)
                }
            }
        }
        .padding(14)
    }

    @ViewBuilder
    private var trends: some View {
        switch family {
        case .systemSmall:
            trendSmall
        default:
            trendMedium
        }
    }

    private var trendSmall: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(snapshot.title)
                .font(.caption2)
                .foregroundStyle(.white.opacity(0.58))
            if hasTrendData {
                Text(currency(snapshot.total))
                    .font(.title3.weight(.bold))
                    .monospacedDigit()
                    .foregroundStyle(.white)
                    .lineLimit(1)
                    .minimumScaleFactor(0.65)
                Spacer(minLength: 0)
                trendDelta
            } else {
                Text("No spending yet")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.white.opacity(0.82))
                Spacer(minLength: 0)
            }
        }
        .padding(14)
    }

    private var trendMedium: some View {
        HStack(alignment: .bottom, spacing: 16) {
            VStack(alignment: .leading, spacing: 6) {
                Text(snapshot.title)
                    .font(.caption2)
                    .foregroundStyle(.white.opacity(0.58))
                if hasTrendData {
                    Text(currency(snapshot.total))
                        .font(.title3.weight(.bold))
                        .monospacedDigit()
                        .foregroundStyle(.white)
                        .lineLimit(1)
                        .minimumScaleFactor(0.65)
                    trendDelta
                } else {
                    Text("No spending yet")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(.white.opacity(0.82))
                }
            }
            if hasTrendData {
                weeklyBars
                    .frame(maxWidth: .infinity, minHeight: 72, maxHeight: 72)
            }
        }
        .padding(14)
    }

    @ViewBuilder
    private var trendDelta: some View {
        // Zero change is neutral — never painted as an increase.
        let delta = snapshot.trendDelta
        let arrow = delta == 0 ? "→" : (delta > 0 ? "↑" : "↓")
        let tint: Color = delta == 0
            ? .white.opacity(0.62)
            : (delta > 0 ? Color(red: 0.35, green: 0.88, blue: 0.61) : Color(red: 1.0, green: 0.52, blue: 0.48))
        let label = delta == 0
            ? "→ No change vs last week"
            : "\(arrow) \(currency(abs(delta))) vs last week"
        Text(label)
            .font(.caption2.weight(.medium))
            .foregroundStyle(tint)
            .lineLimit(1)
            .minimumScaleFactor(0.7)
    }

    private var weeklyBars: some View {
        GeometryReader { geometry in
            let maximum = max(snapshot.trendTotals.map(\.total).max() ?? 0, 0.0001)
            HStack(alignment: .bottom, spacing: 5) {
                ForEach(snapshot.trendTotals) { point in
                    VStack(spacing: 3) {
                        RoundedRectangle(cornerRadius: 3)
                            .fill(ringPalette[0])
                            .frame(height: max(4, geometry.size.height - 18) * point.total / maximum)
                        Text(point.label)
                            .font(.system(size: 7))
                            .foregroundStyle(.white.opacity(0.58))
                            .lineLimit(1)
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottom)
                }
            }
        }
    }

    private func startFraction(upTo index: Int) -> Double {
        snapshot.slices.prefix(index).reduce(0) { $0 + $1.total } / total
    }

    private func currency(_ value: Double) -> String {
        WidgetCurrencyFormatter.string(value)
    }
}

struct AnalyticsRingsWidget: Widget {
    let kind = "AnalyticsRingsWidget"

    var body: some WidgetConfiguration {
        AppIntentConfiguration(kind: kind, intent: AnalyticsModeIntent.self, provider: AnalyticsProvider()) { entry in
            AnalyticsRingsWidgetView(entry: entry)
                .containerBackground(Color(red: 0.035, green: 0.039, blue: 0.055), for: .widget)
        }
        .configurationDisplayName("Spending Analytics")
        .description("Choose a spending view for this widget.")
        .supportedFamilies([.systemSmall, .systemMedium])
    }
}
