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
    Color(red: 0.44, green: 0.30, blue: 0.85),
    Color(red: 0.35, green: 0.55, blue: 0.90),
    Color(red: 0.30, green: 0.75, blue: 0.65),
    Color(red: 0.90, green: 0.60, blue: 0.30),
    Color(red: 0.85, green: 0.40, blue: 0.55),
    Color(red: 0.55, green: 0.45, blue: 0.80),
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
                Text("Today").font(.caption2).foregroundStyle(.secondary)
                Text(currency(entry.todayTotal))
                    .font(.headline.weight(.bold))
                    .minimumScaleFactor(0.5)
                    .lineLimit(1)
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
                        Text(slice.category).font(.caption2)
                        Spacer()
                        Text(currency(slice.total)).font(.caption2.weight(.medium))
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
        let f = NumberFormatter()
        f.numberStyle = .currency
        return f.string(from: NSNumber(value: value)) ?? "\(value)"
    }
}

struct AnalyticsRingsWidget: Widget {
    let kind = "AnalyticsRingsWidget"

    var body: some WidgetConfiguration {
        StaticConfiguration(kind: kind, provider: AnalyticsProvider()) { entry in
            if #available(iOS 17.0, *) {
                AnalyticsRingsWidgetView(entry: entry)
                    .containerBackground(.background, for: .widget)
            } else {
                AnalyticsRingsWidgetView(entry: entry)
            }
        }
        .configurationDisplayName("Spending Rings")
        .description("Today's spending by category.")
        .supportedFamilies([.systemSmall, .systemMedium])
    }
}
