import XCTest
import SwiftData
@testable import SpendTracker

/// Friends cross-device sync: the same push-then-pull contract and safeguards
/// already proven for expenses — remote create/update/delete propagation,
/// duplicate-free repeated pulls, account switching, partial-fetch safety, and
/// multi-page snapshots.
final class FriendSyncIntegrationTests: XCTestCase {
    private static let ownerA = "owner-a"
    private static let ownerB = "owner-b"

    private let t0 = Date(timeIntervalSince1970: 1_700_000_000)
    private let t1 = Date(timeIntervalSince1970: 1_700_000_500)

    // MARK: - Repository double

    /// Enforces the real repository's contract: `fetchAll` throws when the
    /// requested owner doesn't match the session, instead of returning an empty
    /// snapshot that absence-deletion would act on.
    private final class FakeFriendRepository: FriendRepositoring {
        var rows: [UUID: RemoteFriendRow] = [:]
        var sessionOwnerId: String
        var fetchError: Error?
        private(set) var upsertedIDs: [UUID] = []
        private(set) var deletedIDs: [UUID] = []
        private(set) var fetchCount = 0

        init(sessionOwnerId: String) {
            self.sessionOwnerId = sessionOwnerId
        }

        func upsert(_ friend: Friend) async throws {
            upsertedIDs.append(friend.id)
            rows[friend.id] = RemoteFriendRow(
                id: friend.id,
                userId: friend.ownerId ?? sessionOwnerId,
                name: friend.name,
                theyOweUs: friend.theyOweUs,
                weOweThem: friend.weOweThem,
                createdAt: friend.createdAt,
                updatedAt: Date()
            )
        }

        func delete(id: UUID) async throws {
            deletedIDs.append(id)
            rows[id] = nil
        }

        func fetchAll(ownerId: String) async throws -> [RemoteFriendRow] {
            fetchCount += 1
            if let fetchError { throw fetchError }
            guard ownerId.lowercased() == sessionOwnerId.lowercased() else {
                throw AuthError.providerUnavailable("Friend owner mismatch")
            }
            return rows.values
                .filter { $0.userId.lowercased() == sessionOwnerId.lowercased() }
                .sorted { $0.id.uuidString < $1.id.uuidString }
        }
    }

    private struct NoOpExpenseRepository: ExpenseRepositoring {
        func upsert(_ expense: Expense) async throws -> String { expense.id.uuidString }
        func delete(id: UUID) async throws {}
        func fetchAll(ownerId: String) async throws -> [RemoteExpenseRow] { [] }
    }

    private struct NoOpCategoryRepository: CategoryRepositoring {
        func upsert(_ category: SpendTracker.Category) async throws {}
        func delete(id: UUID) async throws {}
        func fetchAll(ownerId: String) async throws -> [RemoteCategoryRow] { [] }
    }

    @MainActor
    private func makeContext() throws -> ModelContext {
        let schema = Schema([Expense.self, Category.self, UserProfile.self, Friend.self])
        let configuration = ModelConfiguration(schema: schema, isStoredInMemoryOnly: true)
        return ModelContext(try ModelContainer(for: schema, configurations: [configuration]))
    }

    @MainActor
    private func makeEngine(
        _ context: ModelContext,
        _ repository: FakeFriendRepository
    ) -> SyncEngine {
        SyncEngine(
            modelContext: context,
            expenseRepository: NoOpExpenseRepository(),
            categoryRepository: NoOpCategoryRepository(),
            friendRepository: repository
        )
    }

    private func row(
        id: UUID = UUID(),
        owner: String,
        name: String = "Maya",
        theyOweUs: Decimal = 10,
        weOweThem: Decimal = 0,
        updatedAt: Date
    ) -> RemoteFriendRow {
        RemoteFriendRow(
            id: id,
            userId: owner,
            name: name,
            theyOweUs: theyOweUs,
            weOweThem: weOweThem,
            createdAt: updatedAt,
            updatedAt: updatedAt
        )
    }

    // MARK: - Cross-device create

    /// The reported bug: a friend added on the phone never appeared in the
    /// simulator under the same account.
    @MainActor
    func testFriendCreatedOnAnotherDeviceAppearsLocallyAfterSync() async throws {
        let context = try makeContext()
        let repository = FakeFriendRepository(sessionOwnerId: Self.ownerA)
        let phoneRow = row(
            owner: Self.ownerA, name: "Leo", theyOweUs: 125, weOweThem: 25, updatedAt: t0
        )
        repository.rows[phoneRow.id] = phoneRow

        await makeEngine(context, repository).syncFriends(ownerId: Self.ownerA)

        let stored = try context.fetch(FetchDescriptor<Friend>())
        XCTAssertEqual(stored.count, 1)
        let pulled = try XCTUnwrap(stored.first)
        XCTAssertEqual(pulled.id, phoneRow.id)
        XCTAssertEqual(pulled.name, "Leo")
        XCTAssertEqual(pulled.theyOweUs, 125)
        XCTAssertEqual(pulled.weOweThem, 25)
        XCTAssertEqual(pulled.netBalance, 100)
        XCTAssertEqual(pulled.ownerId, Self.ownerA)
        XCTAssertEqual(pulled.syncState, .synced)
    }

    @MainActor
    func testPulledFriendIsVisibleInTheFriendsList() async throws {
        let context = try makeContext()
        // Supabase returns the canonical LOWERCASE uuid, while an AuthSession's
        // userId is the uppercase uuidString — the list must still show it.
        let lowercaseOwner = "11111111-2222-3333-4444-555555555555"
        let sessionOwner = lowercaseOwner.uppercased()
        let repository = FakeFriendRepository(sessionOwnerId: lowercaseOwner)
        let remote = row(owner: lowercaseOwner, updatedAt: t0)
        repository.rows[remote.id] = remote

        await makeEngine(context, repository).syncFriends(ownerId: sessionOwner)

        let stored = try context.fetch(FetchDescriptor<Friend>())
        let visible = FriendsViewModel.visibleFriends(stored, ownerId: sessionOwner)
        XCTAssertEqual(visible.count, 1, "a pulled friend must not be hidden by uuid casing")
    }

    @MainActor
    func testRepeatedSyncsAreIdempotentAndCreateNoDuplicates() async throws {
        let context = try makeContext()
        let repository = FakeFriendRepository(sessionOwnerId: Self.ownerA)
        let remote = row(owner: Self.ownerA, updatedAt: t0)
        repository.rows[remote.id] = remote
        let engine = makeEngine(context, repository)

        for _ in 0..<3 {
            await engine.syncFriends(ownerId: Self.ownerA)
        }

        XCTAssertEqual(try context.fetch(FetchDescriptor<Friend>()).count, 1)
        XCTAssertTrue(repository.upsertedIDs.isEmpty, "nothing local changed, nothing to re-push")
    }

    @MainActor
    func testLocalFriendIsPushedBeforeTheSnapshotIsFetched() async throws {
        let context = try makeContext()
        let repository = FakeFriendRepository(sessionOwnerId: Self.ownerA)
        let local = Friend(name: "Maya", ownerId: Self.ownerA, theyOweUs: 30)
        context.insert(local)
        try context.save()

        await makeEngine(context, repository).syncFriends(ownerId: Self.ownerA)

        XCTAssertEqual(repository.upsertedIDs, [local.id])
        XCTAssertEqual(try context.fetch(FetchDescriptor<Friend>()).count, 1)
        XCTAssertEqual(local.syncState, .synced)
    }

    // MARK: - Cross-device update

    @MainActor
    func testNewerRemoteBalanceOverwritesTheLocalSyncedCopy() async throws {
        let context = try makeContext()
        let repository = FakeFriendRepository(sessionOwnerId: Self.ownerA)
        let id = UUID()
        let local = Friend(
            id: id, name: "Maya", ownerId: Self.ownerA, theyOweUs: 10, weOweThem: 0,
            updatedAt: t0, syncState: .synced
        )
        context.insert(local)
        try context.save()
        repository.rows[id] = row(
            id: id, owner: Self.ownerA, name: "Maya R.",
            theyOweUs: 80, weOweThem: 5, updatedAt: t1
        )

        await makeEngine(context, repository).syncFriends(ownerId: Self.ownerA)

        XCTAssertEqual(local.name, "Maya R.")
        XCTAssertEqual(local.theyOweUs, 80)
        XCTAssertEqual(local.weOweThem, 5)
        XCTAssertEqual(local.syncState, .synced)
    }

    @MainActor
    func testLocalDirtyEditIsNotClobberedAndIsPushedInstead() async throws {
        let context = try makeContext()
        let repository = FakeFriendRepository(sessionOwnerId: Self.ownerA)
        let id = UUID()
        let local = Friend(
            id: id, name: "Maya", ownerId: Self.ownerA, theyOweUs: 55,
            updatedAt: t1, syncState: .dirty
        )
        context.insert(local)
        try context.save()
        repository.rows[id] = row(
            id: id, owner: Self.ownerA, theyOweUs: 999, updatedAt: t1.addingTimeInterval(60)
        )

        await makeEngine(context, repository).syncFriends(ownerId: Self.ownerA)

        XCTAssertEqual(local.theyOweUs, 55, "a pending local edit must win its cycle")
        XCTAssertEqual(repository.upsertedIDs, [id])
        XCTAssertEqual(local.syncState, .synced)
    }

    // MARK: - Cross-device delete

    @MainActor
    func testFriendDeletedOnAnotherDeviceIsRemovedLocally() async throws {
        let context = try makeContext()
        let repository = FakeFriendRepository(sessionOwnerId: Self.ownerA)
        context.insert(
            Friend(
                name: "Maya", ownerId: Self.ownerA, theyOweUs: 10,
                updatedAt: t0, syncState: .synced
            )
        )
        try context.save()
        // Absent from the complete snapshot: the phone deleted it.

        await makeEngine(context, repository).syncFriends(ownerId: Self.ownerA)

        XCTAssertTrue(try context.fetch(FetchDescriptor<Friend>()).isEmpty)
    }

    @MainActor
    func testLocalDeletionPropagatesRemotelyAndDoesNotComeBack() async throws {
        let context = try makeContext()
        let repository = FakeFriendRepository(sessionOwnerId: Self.ownerA)
        let id = UUID()
        repository.rows[id] = row(id: id, owner: Self.ownerA, updatedAt: t0)
        context.insert(
            Friend(
                id: id, name: "Maya", ownerId: Self.ownerA, theyOweUs: 10,
                updatedAt: t0, syncState: .deleted
            )
        )
        try context.save()
        let engine = makeEngine(context, repository)

        await engine.syncFriends(ownerId: Self.ownerA)

        XCTAssertEqual(repository.deletedIDs, [id])
        XCTAssertNil(repository.rows[id])
        XCTAssertTrue(try context.fetch(FetchDescriptor<Friend>()).isEmpty)

        await engine.syncFriends(ownerId: Self.ownerA)
        XCTAssertTrue(try context.fetch(FetchDescriptor<Friend>()).isEmpty)
    }

    @MainActor
    func testNeverPushedFriendIsNotDeletedByAbsence() async throws {
        let context = try makeContext()
        let repository = FakeFriendRepository(sessionOwnerId: Self.ownerA)
        // Pushing fails, so it is still `.local` when the pull runs.
        let local = Friend(name: "Maya", ownerId: Self.ownerA, theyOweUs: 10)
        context.insert(local)
        try context.save()

        await makeEngine(context, repository).pullFriends(ownerId: Self.ownerA)

        XCTAssertEqual(try context.fetch(FetchDescriptor<Friend>()).count, 1)
    }

    // MARK: - Failure safety

    @MainActor
    func testFailedFetchAbortsThePullWithoutDeletingAnything() async throws {
        let context = try makeContext()
        let repository = FakeFriendRepository(sessionOwnerId: Self.ownerA)
        repository.fetchError = AuthError.notConfigured
        context.insert(
            Friend(
                name: "Maya", ownerId: Self.ownerA, theyOweUs: 10,
                updatedAt: t0, syncState: .synced
            )
        )
        try context.save()

        let changed = await makeEngine(context, repository).pullFriends(ownerId: Self.ownerA)

        XCTAssertFalse(changed)
        XCTAssertEqual(
            try context.fetch(FetchDescriptor<Friend>()).count, 1,
            "a fetch error must never be read as 'everything was deleted'"
        )
    }

    // MARK: - Owner / session isolation

    @MainActor
    func testOwnerSessionMismatchIsRejectedWithZeroDeletions() async throws {
        let context = try makeContext()
        let repository = FakeFriendRepository(sessionOwnerId: Self.ownerB)
        context.insert(
            Friend(
                name: "Maya", ownerId: Self.ownerA, theyOweUs: 10,
                updatedAt: t0, syncState: .synced
            )
        )
        try context.save()

        let changed = await makeEngine(context, repository).pullFriends(ownerId: Self.ownerA)

        XCTAssertFalse(changed)
        XCTAssertEqual(try context.fetch(FetchDescriptor<Friend>()).count, 1)
    }

    @MainActor
    func testSignedOutSyncFetchesNothingAndDeletesNothing() async throws {
        let context = try makeContext()
        let repository = FakeFriendRepository(sessionOwnerId: Self.ownerA)
        context.insert(
            Friend(
                name: "Maya", ownerId: Self.ownerA, theyOweUs: 10,
                updatedAt: t0, syncState: .synced
            )
        )
        try context.save()

        let changed = await makeEngine(context, repository).syncFriends(ownerId: nil)

        XCTAssertFalse(changed)
        XCTAssertEqual(repository.fetchCount, 0)
        XCTAssertEqual(try context.fetch(FetchDescriptor<Friend>()).count, 1)
    }

    /// Account switching: signing in as B must not touch A's local rows.
    @MainActor
    func testSwitchingAccountsLeavesTheOtherAccountsRowsIntact() async throws {
        let context = try makeContext()
        let repository = FakeFriendRepository(sessionOwnerId: Self.ownerB)
        let theirs = Friend(
            name: "A's friend", ownerId: Self.ownerA, theyOweUs: 900,
            updatedAt: t0, syncState: .synced
        )
        context.insert(theirs)
        try context.save()
        let bRow = row(owner: Self.ownerB, name: "B's friend", updatedAt: t0)
        repository.rows[bRow.id] = bRow

        await makeEngine(context, repository).syncFriends(ownerId: Self.ownerB)

        let stored = try context.fetch(FetchDescriptor<Friend>())
        XCTAssertEqual(stored.count, 2, "A's row must survive B's pull")
        XCTAssertEqual(
            FriendsViewModel.visibleFriends(stored, ownerId: Self.ownerB).map(\.name),
            ["B's friend"]
        )
        XCTAssertEqual(
            FriendsViewModel.visibleFriends(stored, ownerId: Self.ownerA).map(\.name),
            ["A's friend"]
        )
    }

    @MainActor
    func testRemoteRowBelongingToAnotherAccountIsNotInserted() async throws {
        let context = try makeContext()
        let repository = FakeFriendRepository(sessionOwnerId: Self.ownerA)
        let foreign = row(owner: Self.ownerB, updatedAt: t0)
        repository.rows[foreign.id] = foreign

        await makeEngine(context, repository).syncFriends(ownerId: Self.ownerA)

        XCTAssertTrue(try context.fetch(FetchDescriptor<Friend>()).isEmpty)
    }

    // MARK: - Multi-page snapshot

    @MainActor
    func testMultiPageSnapshotIsReconciledWithoutSpuriousDeletions() async throws {
        let context = try makeContext()
        let repository = FakeFriendRepository(sessionOwnerId: Self.ownerA)
        var localIDs: [UUID] = []
        for index in 0..<1_100 {
            let id = UUID()
            repository.rows[id] = row(
                id: id, owner: Self.ownerA, name: "F\(index)",
                theyOweUs: Decimal(index + 1), updatedAt: t0
            )
            if index.isMultiple(of: 2) {
                localIDs.append(id)
                context.insert(
                    Friend(
                        id: id, name: "F\(index)", ownerId: Self.ownerA,
                        theyOweUs: Decimal(index + 1), updatedAt: t0, syncState: .synced
                    )
                )
            }
        }
        try context.save()

        await makeEngine(context, repository).syncFriends(ownerId: Self.ownerA)

        let stored = try context.fetch(FetchDescriptor<Friend>())
        XCTAssertEqual(stored.count, 1_100)
        XCTAssertEqual(Set(stored.map(\.id)).count, 1_100, "no duplicates across pages")
        for id in localIDs {
            XCTAssertTrue(stored.contains { $0.id == id }, "existing synced row was deleted")
        }
    }

    // MARK: - Reconciliation rules + decoding

    func testPendingLocalStatesAreNeverDeletedByAbsence() {
        let localOnly = UUID()
        let dirty = UUID()
        let tombstone = UUID()
        let plan = FriendReconciliation.plan(
            local: [
                .init(id: localOnly, ownerId: Self.ownerA, updatedAt: t0, syncState: .local),
                .init(id: dirty, ownerId: Self.ownerA, updatedAt: t0, syncState: .dirty),
                .init(id: tombstone, ownerId: Self.ownerA, updatedAt: t0, syncState: .deleted),
            ],
            remote: [],
            activeOwnerId: Self.ownerA
        )

        XCTAssertTrue(plan.deletions.isEmpty)
    }

    func testDuplicatedRemoteIDsAcrossPagesDoNotProduceDuplicateInserts() {
        let id = UUID()
        let plan = FriendReconciliation.plan(
            local: [],
            remote: [
                row(id: id, owner: Self.ownerA, theyOweUs: 10, updatedAt: t0),
                row(id: id, owner: Self.ownerA, theyOweUs: 20, updatedAt: t1),
            ],
            activeOwnerId: Self.ownerA
        )

        XCTAssertEqual(plan.inserts.count, 1)
        XCTAssertEqual(plan.inserts.first?.theyOweUs, 20)
    }

    func testEmptyActiveOwnerProducesNoPlan() {
        let plan = FriendReconciliation.plan(
            local: [.init(id: UUID(), ownerId: Self.ownerA, updatedAt: t0, syncState: .synced)],
            remote: [],
            activeOwnerId: "   "
        )

        XCTAssertTrue(plan.isEmpty)
    }

    func testRemoteFriendRowDecodesPostgRESTShapes() throws {
        let id = UUID()
        let json = """
        [
          {
            "id": "\(id.uuidString)",
            "user_id": "owner-a",
            "name": "Maya",
            "they_owe_us": "32.50",
            "we_owe_them": 12.25,
            "created_at": "2026-10-01T10:00:00+00:00",
            "updated_at": "2026-10-01T10:00:00.123456Z"
          }
        ]
        """.data(using: .utf8)!

        let rows = try JSONDecoder().decode([RemoteFriendRow].self, from: json)

        XCTAssertEqual(rows.count, 1)
        XCTAssertEqual(rows[0].id, id)
        XCTAssertEqual(rows[0].theyOweUs, Decimal(string: "32.50"))
        XCTAssertEqual(rows[0].weOweThem, Decimal(string: "12.25"))
    }
}
