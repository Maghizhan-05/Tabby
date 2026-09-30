import WidgetKit
import SwiftUI

@main
struct SpendTrackerWidgetBundle: WidgetBundle {
    var body: some Widget {
        AnalyticsRingsWidget()
        ExpenseConfirmationLiveActivity()
    }
}
