import Foundation
import SwiftData

/// A locally-owned balance record for one person. Its UUID is also the cloud key.
@Model
final class Friend {
    @Attribute(.unique) var id: UUID
    var name: String
    /// Supabase user id that owns this record. Nil only for legacy rows created
    /// before ownership partitioning; those are claimed by the next signed-in user.
    var ownerId: String?
    var theyOweUs: Decimal
    var weOweThem: Decimal
    var createdAt: Date
    var updatedAt: Date
    var syncStateRaw: Int

    var syncState: SyncState {
        get { SyncState(rawValue: syncStateRaw) ?? .local }
        set { syncStateRaw = newValue.rawValue }
    }

    /// Positive means this friend owes us; negative means we owe them.
    var netBalance: Decimal { theyOweUs - weOweThem }

    init(
        id: UUID = UUID(),
        name: String,
        ownerId: String? = nil,
        theyOweUs: Decimal = 0,
        weOweThem: Decimal = 0,
        createdAt: Date = Date(),
        updatedAt: Date = Date(),
        syncState: SyncState = .local
    ) {
        self.id = id
        self.name = name.trimmingCharacters(in: .whitespacesAndNewlines)
        self.ownerId = ownerId
        self.theyOweUs = theyOweUs
        self.weOweThem = weOweThem
        self.createdAt = createdAt
        self.updatedAt = updatedAt
        self.syncStateRaw = syncState.rawValue
    }
}
