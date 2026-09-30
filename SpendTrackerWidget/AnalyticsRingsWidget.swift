import WidgetKit
import SwiftUI

struct AnalyticsEntry: TimelineEntry {
    let date: Date
    let todayTotal: Double
    let slices: [WidgetDataProvider.RingSlice]
}

struct AnalyticsProvider: TimelineProvider {
    func placeholder(in context: Context) -> AnalyticsEntry {
        AnalyticsEntry(date: Date(), todayTotal: 42.0, slices: [
            .init(category: "Food", total: 20),
            .init(category: "Transport", total: 12),
            .init(category: "Other", total: 10),
        ])
    }

    func getSnapshot(in context: Context, completion: @escaping (AnalyticsEntry) -> Void) {
        let snap = WidgetDataProvider.currentSnapshot()
        completion(AnalyticsEntry(date: Date(), todayTotal: snap.todayTotal, slices: snap.slices))
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<AnalyticsEntry>) -> Void) {
        let snap = WidgetDataProvider.currentSnapshot()
        let entry = AnalyticsEntry(date: Date(), todayTotal: snap.todayTotal, slices: snap.slices)
        let next = Calendar.current.date(byAdding: .minute, value: 30, to: Date()) ?? Date()
        completion(Timeline(entries: [entry], policy: .after(next)))
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

    private var total: Double { max(entry.slices.reduce(0) { $0 + $1.total }, 0.0001) }

    var body: some View {
        switch family {
        case .systemSmall:
            small
        default:
            medium
        }
    }

    private var ring: some View {
        ZStack {
            ForEach(Array(entry.slices.enumerated()), id: \.element.id) { index, slice in
                let start = startFraction(upTo: index)
                let end = start + slice.total / total
                Circle()
                    .trim(from: start, to: end)
                    .stroke(ringPalette[index % ringPalette.count],
                            style: StrokeStyle(lineWidth: 12, lineCap: .butt))
                    .rotationEffect(.degrees(-90))
            }
            VStack(spacing: 0) {
                Text("Today").font(.caption2).foregroundStyle(.white.opacity(0.58))
                Text(currency(entry.todayTotal))
                    .font(.headline.weight(.bold))
                    .monospacedDigit()
                    .foregroundStyle(.white)
                    .minimumScaleFactor(0.62)
                    .lineLimit(1)
                    .layoutPriority(1)
            }
        }
    }

    private var small: some View {
        ring.padding(12).widgetURL(URL(string: "spendtracker://quick-entry"))
    }

    private var medium: some View {
        HStack(spacing: 16) {
            ring.frame(width: 90, height: 90)
            VStack(alignment: .leading, spacing: 4) {
                ForEach(Array(entry.slices.prefix(4).enumerated()), id: \.element.id) { index, slice in
                    HStack(spacing: 6) {
                        Circle().fill(ringPalette[index % ringPalette.count]).frame(width: 7, height: 7)
                        Text(slice.category).font(.caption2).foregroundStyle(.white.opacity(0.78))
                        Spacer()
                        Text(currency(slice.total)).font(.caption2.weight(.medium).monospacedDigit()).foregroundStyle(.white).lineLimit(1).minimumScaleFactor(0.7)
                    }
                }
                if entry.slices.isEmpty {
                    Text("No spending today").font(.caption2).foregroundStyle(.secondary)
                }
            }
        }
        .padding(14)
        .widgetURL(URL(string: "spendtracker://quick-entry"))
    }

    private func startFraction(upTo index: Int) -> Double {
        entry.slices.prefix(index).reduce(0) { $0 + $1.total } / total
    }

    private func currency(_ value: Double) -> String {
        WidgetCurrencyFormatter.string(value)
    }
}

struct AnalyticsRingsWidget: Widget {
    let kind = "AnalyticsRingsWidget"

    var body: some WidgetConfiguration {
        StaticConfiguration(kind: kind, provider: AnalyticsProvider()) { entry in
            if #available(iOS 17.0, *) {
                AnalyticsRingsWidgetView(entry: entry)
                    .containerBackground(Color(red: 0.035, green: 0.039, blue: 0.055), for: .widget)
            } else {
                AnalyticsRingsWidgetView(entry: entry)
            }
        }
        .configurationDisplayName("Spending Rings")
        .description("Today's spending by category.")
        .supportedFamilies([.systemSmall, .systemMedium])
    }
}
