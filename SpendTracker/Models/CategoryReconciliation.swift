import Foundation

/// A row as it exists in the backend `categories` table. Decoding mirrors
/// `RemoteExpenseRow`/`RemoteFriendRow`.
struct RemoteCategoryRow: Decodable, Equatable {
    let id: UUID
    let userId: String
    let name: String
    let isDefault: Bool
    let sortOrder: Int

    private enum CodingKeys: String, CodingKey {
        case id
        case userId = "user_id"
        case name
        case isDefault = "is_default"
        case sortOrder = "sort_order"
    }

    init(id: UUID, userId: String, name: String, isDefault: Bool = false, sortOrder: Int = 0) {
        self.id = id
        self.userId = userId
        self.name = name
        self.isDefault = isDefault
        self.sortOrder = sortOrder
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        let rawId = try container.decode(String.self, forKey: .id)
        guard let id = UUID(uuidString: rawId) else {
            throw DecodingError.dataCorruptedError(
                forKey: .id, in: container, debugDescription: "Not a uuid: \(rawId)"
            )
        }
        self.id = id
        self.userId = try container.decode(String.self, forKey: .userId)
        self.name = try container.decode(String.self, forKey: .name)
        self.isDefault = try container.decodeIfPresent(Bool.self, forKey: .isDefault) ?? false
        self.sortOrder = try container.decodeIfPresent(Int.self, forKey: .sortOrder) ?? 0
    }
}

/// Pure merge rules for bidirectional custom-category sync.
///
/// Categories differ from expenses and friends in one important way: the seeded
/// defaults exist independently on every device with `ownerId == nil`, and they
/// must never be pushed, pulled, or deleted by absence. Only custom categories
/// — the ones a user creates — participate in sync.
enum CategoryReconciliation {
    struct Plan: Equatable {
        var inserts: [RemoteCategoryRow] = []
        var updates: [RemoteCategoryRow] = []
        var deletions: [UUID] = []

        var isEmpty: Bool { inserts.isEmpty && updates.isEmpty && deletions.isEmpty }
    }

    struct LocalRecord: Equatable {
        let id: UUID
        let ownerId: String?
        let name: String
        let sortOrder: Int
        let isDefault: Bool
        let syncState: SyncState
        /// True when the backend has already seen this record.
        let hasRemoteIdentity: Bool

        init(
            id: UUID,
            ownerId: String?,
            name: String,
            sortOrder: Int = 0,
            isDefault: Bool = false,
            syncState: SyncState = .local,
            hasRemoteIdentity: Bool = false
        ) {
            self.id = id
            self.ownerId = ownerId
            self.name = name
            self.sortOrder = sortOrder
            self.isDefault = isDefault
            self.syncState = syncState
            self.hasRemoteIdentity = hasRemoteIdentity
        }
    }

    /// Builds the merge plan.
    ///
    /// Rules:
    /// - A seeded default (`isDefault`) is never inserted, updated or deleted.
    /// - A remote row absent locally → insert as `.synced`.
    /// - A local `.synced` row whose remote name/order differs → update.
    /// - A pending local row (`.local` / `.dirty` / `.deleted`) keeps its local
    ///   value this cycle; the following push propagates it.
    /// - A local row absent from the snapshot is deleted iff the backend had
    ///   seen it, matching the expense/friend rule that stops resurrection.
    /// - A local row matching a remote row **by name** is adopted rather than
    ///   duplicated: two devices that each created "Coffee" offline converge on
    ///   the remote row's identity instead of showing it twice.
    static func plan(
        local: [LocalRecord],
        remote: [RemoteCategoryRow],
        activeOwnerId: String
    ) -> Plan {
        guard let owner = ExpenseOwnership.normalized(activeOwnerId) else { return Plan() }

        var plan = Plan()

        // Last row wins for a duplicated id across pages.
        var remoteByID: [UUID: RemoteCategoryRow] = [:]
        for row in remote where ExpenseOwnership.normalized(row.userId) == owner && !row.isDefault {
            remoteByID[row.id] = row
        }

        let localForOwner = local.filter {
            !$0.isDefault && ExpenseOwnership.normalized($0.ownerId) == owner
        }
        let localByID = Dictionary(localForOwner.map { ($0.id, $0) }, uniquingKeysWith: { a, _ in a })
        // Name collisions are resolved against rows the backend has NOT seen:
        // a local-only "Coffee" adopts the remote "Coffee" instead of duplicating.
        var unsyncedLocalByName: [String: LocalRecord] = [:]
        for record in localForOwner where !record.hasRemoteIdentity && record.syncState == .local {
            let key = record.name.lowercased()
            if unsyncedLocalByName[key] == nil { unsyncedLocalByName[key] = record }
        }

        for row in remoteByID.values.sorted(by: { $0.id.uuidString < $1.id.uuidString }) {
            guard let localRecord = localByID[row.id] else {
                // A local-only row with the same name is about to be replaced by
                // this remote identity, so drop the duplicate and take the remote.
                if let duplicate = unsyncedLocalByName[row.name.lowercased()] {
                    plan.deletions.append(duplicate.id)
                }
                plan.inserts.append(row)
                continue
            }

            guard localRecord.syncState == .synced else { continue }
            if localRecord.name != row.name || localRecord.sortOrder != row.sortOrder {
                plan.updates.append(row)
            }
        }

        for record in localForOwner where remoteByID[record.id] == nil {
            guard record.syncState == .synced || record.hasRemoteIdentity else { continue }
            plan.deletions.append(record.id)
        }

        plan.inserts.sort { $0.id.uuidString < $1.id.uuidString }
        plan.updates.sort { $0.id.uuidString < $1.id.uuidString }
        plan.deletions = Array(Set(plan.deletions)).sorted { $0.uuidString < $1.uuidString }
        return plan
    }
}
