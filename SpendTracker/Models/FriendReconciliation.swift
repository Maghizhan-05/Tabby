import Foundation

/// A row as it exists in the backend `friends` table. Decoding mirrors
/// `RemoteExpenseRow`: PostgREST returns `numeric` as a JSON number or a string
/// and `timestamptz` with or without fractional seconds.
struct RemoteFriendRow: Decodable, Equatable {
    let id: UUID
    let userId: String
    let name: String
    let theyOweUs: Decimal
    let weOweThem: Decimal
    let createdAt: Date
    let updatedAt: Date

    private enum CodingKeys: String, CodingKey {
        case id
        case userId = "user_id"
        case name
        case theyOweUs = "they_owe_us"
        case weOweThem = "we_owe_them"
        case createdAt = "created_at"
        case updatedAt = "updated_at"
    }

    init(
        id: UUID,
        userId: String,
        name: String,
        theyOweUs: Decimal,
        weOweThem: Decimal,
        createdAt: Date,
        updatedAt: Date
    ) {
        self.id = id
        self.userId = userId
        self.name = name
        self.theyOweUs = theyOweUs
        self.weOweThem = weOweThem
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
        name = try container.decode(String.self, forKey: .name)
        theyOweUs = try Self.decodeDecimal(container, forKey: .theyOweUs)
        weOweThem = try Self.decodeDecimal(container, forKey: .weOweThem)
        createdAt = try Self.decodeDate(container, forKey: .createdAt)
        updatedAt = try Self.decodeDate(container, forKey: .updatedAt)
    }

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
        guard let date = RemoteExpenseRow.parseTimestamp(text) else {
            throw DecodingError.dataCorruptedError(
                forKey: key, in: container, debugDescription: "Not a timestamp: \(text)"
            )
        }
        return date
    }
}

/// Pure merge rules for reconciling local friends against an owner-scoped,
/// *provably complete* remote snapshot. Same contract and same rules as
/// `ExpenseReconciliation` — see that type for the ordering requirement: the
/// caller MUST complete a push before fetching, because absence from the
/// snapshot is read as "deleted on another device".
enum FriendReconciliation {

    struct Update: Equatable {
        let id: UUID
        let row: RemoteFriendRow
    }

    struct Plan: Equatable {
        var inserts: [RemoteFriendRow] = []
        var updates: [Update] = []
        var deletions: [UUID] = []

        var isEmpty: Bool {
            inserts.isEmpty && updates.isEmpty && deletions.isEmpty
        }
    }

    struct LocalRecord: Equatable {
        let id: UUID
        let ownerId: String?
        let updatedAt: Date
        let syncState: SyncState
        /// True when this record was previously uploaded. Only such a record
        /// may be deleted by absence — see `ExpenseReconciliation.LocalRecord`.
        let hasRemoteIdentity: Bool

        init(
            id: UUID,
            ownerId: String?,
            updatedAt: Date,
            syncState: SyncState,
            hasRemoteIdentity: Bool = false
        ) {
            self.id = id
            self.ownerId = ownerId
            self.updatedAt = updatedAt
            self.syncState = syncState
            self.hasRemoteIdentity = hasRemoteIdentity
        }
    }

    /// Rules (identical to expenses):
    /// - Remote row absent locally → insert, `.synced`.
    /// - Local `.synced` and the remote row is strictly newer → overwrite.
    /// - Local `.dirty` / `.local` / `.deleted` → remote ignored this cycle;
    ///   the pending local change wins and propagates on the next push.
    /// - A local row absent from the snapshot is deleted iff it was previously
    ///   uploaded (`.synced`, or any state carrying a remote identity), which
    ///   prevents a row deleted elsewhere from being re-uploaded forever.
    /// - A row that never reached the backend is NEVER deleted by absence.
    /// - Rows belonging to another account are ignored in both directions.
    static func plan(
        local: [LocalRecord],
        remote: [RemoteFriendRow],
        activeOwnerId: String
    ) -> Plan {
        guard let owner = ExpenseOwnership.normalized(activeOwnerId) else { return Plan() }

        let remoteForOwner = remote.filter {
            ExpenseOwnership.normalized($0.userId) == owner
        }
        // Last row wins for a duplicated id, so overlapping pages are idempotent.
        var remoteByID: [UUID: RemoteFriendRow] = [:]
        for row in remoteForOwner { remoteByID[row.id] = row }

        let localForOwner = local.filter {
            ExpenseOwnership.isAccessible(recordOwnerId: $0.ownerId, activeOwnerId: owner)
        }
        var localByID: [UUID: LocalRecord] = [:]
        for record in localForOwner { localByID[record.id] = record }

        var plan = Plan()

        for id in remoteByID.keys.sorted(by: { $0.uuidString < $1.uuidString }) {
            guard let row = remoteByID[id] else { continue }
            guard let localRecord = localByID[id] else {
                plan.inserts.append(row)
                continue
            }
            guard localRecord.syncState == .synced else { continue }
            if row.updatedAt > localRecord.updatedAt {
                plan.updates.append(Update(id: id, row: row))
            }
        }

        // Absence means "deleted elsewhere" only for a row the backend has seen.
        for record in localForOwner where remoteByID[record.id] == nil {
            guard record.syncState == .synced || record.hasRemoteIdentity else { continue }
            plan.deletions.append(record.id)
        }
        plan.deletions.sort { $0.uuidString < $1.uuidString }

        return plan
    }
}
