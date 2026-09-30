import ActivityKit
import WidgetKit
import SwiftUI

private let confirmAccent = Color(red: 0.44, green: 0.30, blue: 0.85)

/// The Live Activity / Dynamic Island UI for expense confirmation.
struct ExpenseConfirmationLiveActivity: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: ExpenseConfirmationAttributes.self) { context in
            // Lock screen / banner presentation.
            LockScreenConfirmation(state: context.state)
                .padding()
                .activityBackgroundTint(Color.black.opacity(0.85))
                .activitySystemActionForegroundColor(.white)
        } dynamicIsland: { context in
            DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    ConfirmationRing()
                        .frame(width: 44, height: 44)
                        .padding(.leading, 6)
                }
                DynamicIslandExpandedRegion(.trailing) {
                    VStack(alignment: .trailing, spacing: 2) {
                        Text(currency(context.state.amount))
                            .font(.title3.weight(.bold))
                        Text(context.state.category)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    .padding(.trailing, 6)
                }
                DynamicIslandExpandedRegion(.bottom) {
                    Text("Logged in Tabby")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
            } compactLeading: {
                Image(systemName: "checkmark.circle.fill")
                    .foregroundStyle(confirmAccent)
            } compactTrailing: {
                Text(currency(context.state.amount))
                    .font(.caption2.weight(.semibold))
            } minimal: {
                Image(systemName: "checkmark.circle.fill")
                    .foregroundStyle(confirmAccent)
            }
            .keylineTint(confirmAccent)
        }
    }

    private func currency(_ value: Double) -> String {
        let f = NumberFormatter()
        f.numberStyle = .currency
        return f.string(from: NSNumber(value: value)) ?? "\(value)"
    }
}

private struct LockScreenConfirmation: View {
    let state: ExpenseConfirmationAttributes.ContentState

    var body: some View {
        HStack(spacing: 14) {
            ConfirmationRing().frame(width: 40, height: 40)
            VStack(alignment: .leading, spacing: 2) {
                Text("Expense Logged")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(.white)
                Text(state.category)
                    .font(.caption)
                    .foregroundStyle(.white.opacity(0.7))
            }
            Spacer()
            Text(currency(state.amount))
                .font(.title3.weight(.bold))
                .foregroundStyle(.white)
        }
    }

    private func currency(_ value: Double) -> String {
        let f = NumberFormatter()
        f.numberStyle = .currency
        return f.string(from: NSNumber(value: value)) ?? "\(value)"
    }
}

/// An animated confirmation ring.
private struct ConfirmationRing: View {
    @State private var trim: CGFloat = 0

    var body: some View {
        ZStack {
            Circle()
                .stroke(confirmAccent.opacity(0.25), lineWidth: 4)
            Circle()
                .trim(from: 0, to: trim)
                .stroke(confirmAccent, style: StrokeStyle(lineWidth: 4, lineCap: .round))
                .rotationEffect(.degrees(-90))
            Image(systemName: "checkmark")
                .font(.system(size: 14, weight: .bold))
                .foregroundStyle(confirmAccent)
        }
        .onAppear {
            withAnimation(.easeOut(duration: 0.6)) { trim = 1 }
        }
    }
}
