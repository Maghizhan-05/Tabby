import Foundation
import SwiftData

/// Sync state for offline-first records.
enum SyncState: Int, Codable {
    case local = 0      // Created locally, never pushed
    case synced = 1     // Pushed and confirmed by backend
    case dirty = 2      // Modified locally after a prior sync
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

    var syncState: SyncState {
        get { SyncState(rawValue: syncStateRaw) ?? .local }
        set { syncStateRaw = newValue.rawValue }
    }

    init(
        id: UUID = UUID(),
        amount: Decimal,
        categoryName: String,
        note: String? = nil,
        date: Date = Date(),
        createdAt: Date = Date(),
        updatedAt: Date = Date(),
        syncState: SyncState = .local,
        remoteId: String? = nil
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
    }

    static func normalizedNote(_ note: String?) -> String? {
        guard let trimmed = note?.trimmingCharacters(in: .whitespacesAndNewlines),
              !trimmed.isEmpty else { return nil }
        return trimmed
    }
}
