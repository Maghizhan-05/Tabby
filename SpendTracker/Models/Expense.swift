import Foundation
import SwiftData

/// Sync state for offline-first records.
enum SyncState: Int, Codable {
    case local = 0      // Created locally, never pushed
    case synced = 1     // Pushed and confirmed by backend
    case dirty = 2      // Modified locally after a prior sync
    case deleted = 3    // Removed locally; pending remote deletion
}

@Model
final class Expense {
    static let maximumNoteLength = 120

    @Attribute(.unique) var id: UUID
    var amount: Decimal
    var categoryName: String
    var note: String?
    var date: Date
    var createdAt: Date
    var updatedAt: Date
    var syncStateRaw: Int
    var remoteId: String?
    /// Supabase user id that owns this record. Nil only for legacy rows created
    /// before ownership partitioning; those are claimed by the next signed-in
    /// user on the first push and are never re-stamped afterwards.
    var ownerId: String?
    /// Monotonic local revision, bumped on every local edit. A push may only
    /// mark a record `.synced` when the revision it uploaded is still current,
    /// so an edit made while an upload is in flight is never lost.
    var revision: Int = 0

    var syncState: SyncState {
        get { SyncState(rawValue: syncStateRaw) ?? .local }
        set { syncStateRaw = newValue.rawValue }
    }

    init(
        id: UUID = UUID(),
        ownerId: String? = nil,
        amount: Decimal,
        categoryName: String,
        note: String? = nil,
        date: Date = Date(),
        createdAt: Date = Date(),
        updatedAt: Date = Date(),
        syncState: SyncState = .local,
        remoteId: String? = nil,
        revision: Int = 0
    ) {
        self.id = id
        self.amount = amount
        self.categoryName = categoryName
        self.note = Self.normalizedNote(note)
        self.date = date
        self.createdAt = createdAt
        self.updatedAt = updatedAt
        self.syncStateRaw = syncState.rawValue
        self.remoteId = remoteId
        self.ownerId = ownerId
        self.revision = revision
    }

    static func normalizedNote(_ note: String?) -> String? {
        guard let trimmed = note?.trimmingCharacters(in: .whitespacesAndNewlines),
              !trimmed.isEmpty else { return nil }
        return trimmed
    }
}
