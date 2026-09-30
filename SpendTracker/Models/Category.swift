import Foundation
import SwiftData

@Model
final class Category {
    @Attribute(.unique) var id: UUID
    var name: String
    var isDefault: Bool
    var sortOrder: Int
    /// Sync state mirroring `Expense.syncStateRaw`. Defaults to `.synced` (1) so
    /// default/seeded categories are not pushed until they are actually changed,
    /// while newly-created custom categories start as `.local` (0) and get pushed.
    var syncStateRaw: Int
    var remoteId: String?

    var syncState: SyncState {
        get { SyncState(rawValue: syncStateRaw) ?? .local }
        set { syncStateRaw = newValue.rawValue }
    }

    init(
        id: UUID = UUID(),
        name: String,
        isDefault: Bool = false,
        sortOrder: Int = 0,
        syncState: SyncState = .local,
        remoteId: String? = nil
    ) {
        self.id = id
        self.name = name
        self.isDefault = isDefault
        self.sortOrder = sortOrder
        self.syncStateRaw = syncState.rawValue
        self.remoteId = remoteId
    }
}
