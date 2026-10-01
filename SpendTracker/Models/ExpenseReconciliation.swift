import Foundation

/// A row as it exists in the backend `expenses` table. Decoding is deliberately
/// tolerant: PostgREST returns `numeric` as a JSON number or a string depending
/// on configuration, and `timestamptz` with or without fractional seconds.
struct RemoteExpenseRow: Decodable, Equatable {
    let id: UUID
    let userId: String
    let amount: Decimal
    let categoryName: String
    let note: String?
    let date: Date
    let createdAt: Date
    let updatedAt: Date

    private enum CodingKeys: String, CodingKey {
        case id
        case userId = "user_id"
        case amount
        case categoryName = "category_name"
        case note
        case date
        case createdAt = "created_at"
        case updatedAt = "updated_at"
    }

    init(
        id: UUID,
        userId: String,
        amount: Decimal,
        categoryName: String,
        note: String? = nil,
        date: Date,
        createdAt: Date,
        updatedAt: Date
    ) {
        self.id = id
        self.userId = userId
        self.amount = amount
        self.categoryName = categoryName
        self.note = note
        self.date = date
        self.createdAt = createdAt
        self.updatedAt = updatedAt
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)

        let rawID = try container.decode(String.self, forKey: .id)
        guard let id = UUID(uuidString: rawID) else {
            throw DecodingError.dataCorruptedError(
                forKey: .id, in: container, debugDescription: "Not a uuid: \(rawID)"
            )
        }
        self.id = id
        userId = try container.decode(String.self, forKey: .userId)
        amount = try Self.decodeDecimal(container, forKey: .amount)
        categoryName = try container.decode(String.self, forKey: .categoryName)
        note = try container.decodeIfPresent(String.self, forKey: .note)
        date = try Self.decodeDate(container, forKey: .date)
        createdAt = try Self.decodeDate(container, forKey: .createdAt)
        updatedAt = try Self.decodeDate(container, forKey: .updatedAt)
    }

    /// `numeric` may arrive as a JSON number or a quoted string; both must keep
    /// their exact decimal value (never routed through Double).
    private static func decodeDecimal(
        _ container: KeyedDecodingContainer<CodingKeys>,
        forKey key: CodingKeys
    ) throws -> Decimal {
        if let text = try? container.decode(String.self, forKey: key),
           let value = Decimal(string: text) {
            return value
        }
        if let value = try? container.decode(Decimal.self, forKey: key) {
            return value
        }
        throw DecodingError.dataCorruptedError(
            forKey: key, in: container, debugDescription: "Not a decimal"
        )
    }

    private static func decodeDate(
        _ container: KeyedDecodingContainer<CodingKeys>,
        forKey key: CodingKeys
    ) throws -> Date {
        let text = try container.decode(String.self, forKey: key)
        guard let date = Self.parseTimestamp(text) else {
            throw DecodingError.dataCorruptedError(
                forKey: key, in: container, debugDescription: "Not a timestamp: \(text)"
            )
        }
        return date
    }

    /// Parses the timestamp shapes PostgREST emits: with/without fractional
    /// seconds, and with a `+00:00`, `Z`, or space-separated offset.
    static func parseTimestamp(_ text: String) -> Date? {
        let normalized = text.replacingOccurrences(of: " ", with: "T")
        let withFraction = ISO8601DateFormatter()
        withFraction.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        if let date = withFraction.date(from: normalized) { return date }
        let plain = ISO8601DateFormatter()
        plain.formatOptions = [.withInternetDateTime]
        if let date = plain.date(from: normalized) { return date }
        // No explicit offset: Postgres timestamptz is returned in UTC.
        let utc = ISO8601DateFormatter()
        utc.formatOptions = [.withInternetDateTime]
        return utc.date(from: normalized + "Z")
    }
}

/// Pure merge rules for reconciling a local SwiftData store against an
/// owner-scoped, *provably complete* remote snapshot. No SwiftData or Supabase
/// imports, so every rule is unit-testable in isolation.
///
/// Ordering contract: the caller MUST complete a push before fetching the
/// snapshot. Absence from the snapshot is read as "deleted on another device",
/// which is only sound once local work has been uploaded.
enum ExpenseReconciliation {

    /// A single field-level update to apply to an existing local record.
    struct Update: Equatable {
        let id: UUID
        let row: RemoteExpenseRow
    }

    struct Plan: Equatable {
        /// Remote rows with no local counterpart: insert as `.synced`.
        var inserts: [RemoteExpenseRow] = []
        /// Local `.synced` rows the remote has a newer version of.
        var updates: [Update] = []
        /// Ids of local `.synced` rows absent from the complete remote
        /// snapshot — deleted on another device, so remove them locally.
        var deletions: [UUID] = []

        var isEmpty: Bool {
            inserts.isEmpty && updates.isEmpty && deletions.isEmpty
        }
    }

    /// The local state a record is in, extracted so the rules can be tested
    /// without constructing SwiftData models.
    struct LocalRecord: Equatable {
        let id: UUID
        let ownerId: String?
        let updatedAt: Date
        let syncState: SyncState

        init(id: UUID, ownerId: String?, updatedAt: Date, syncState: SyncState) {
            self.id = id
            self.ownerId = ownerId
            self.updatedAt = updatedAt
            self.syncState = syncState
        }
    }

    /// Builds the merge plan.
    ///
    /// Rules:
    /// - Remote row absent locally → insert, `.synced`.
    /// - Local `.synced` and the remote row is strictly newer → overwrite from remote.
    /// - Local `.dirty` / `.local` / `.deleted` → the remote row is ignored this
    ///   cycle; the pending local change wins and the next push propagates it
    ///   (documented last-writer-wins, never a silent drop).
    /// - Local `.synced` row absent from the snapshot → delete locally.
    /// - Local `.local` / `.dirty` / `.deleted` rows are NEVER deleted by
    ///   absence: they have not been pushed yet, or their delete is in flight.
    /// - Rows belonging to another account (or a remote row whose `user_id`
    ///   isn't the active owner) are ignored entirely in both directions.
    static func plan(
        local: [LocalRecord],
        remote: [RemoteExpenseRow],
        activeOwnerId: String
    ) -> Plan {
        guard let owner = ExpenseOwnership.normalized(activeOwnerId) else { return Plan() }

        // Defense in depth: never let a row from another account into the plan,
        // even if the backend or a caller handed us one.
        let remoteForOwner = remote.filter {
            ExpenseOwnership.normalized($0.userId) == owner
        }
        // Last row wins for a duplicated id so repeated/overlapping pages are
        // idempotent rather than producing duplicate inserts.
        var remoteByID: [UUID: RemoteExpenseRow] = [:]
        for row in remoteForOwner { remoteByID[row.id] = row }

        let localForOwner = local.filter {
            ExpenseOwnership.isAccessible(recordOwnerId: $0.ownerId, activeOwnerId: owner)
        }
        var localByID: [UUID: LocalRecord] = [:]
        for record in localForOwner { localByID[record.id] = record }

        var plan = Plan()

        // Deterministic ordering keeps the plan (and its tests) stable.
        for id in remoteByID.keys.sorted(by: { $0.uuidString < $1.uuidString }) {
            guard let row = remoteByID[id] else { continue }
            guard let localRecord = localByID[id] else {
                plan.inserts.append(row)
                continue
            }
            // A pending local change always wins this cycle.
            guard localRecord.syncState == .synced else { continue }
            if row.updatedAt > localRecord.updatedAt {
                plan.updates.append(Update(id: id, row: row))
            }
        }

        for record in localForOwner
        where record.syncState == .synced && remoteByID[record.id] == nil {
            plan.deletions.append(record.id)
        }
        plan.deletions.sort { $0.uuidString < $1.uuidString }

        return plan
    }
}
