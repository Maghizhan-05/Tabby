import XCTest
import SwiftData
@testable import SpendTracker

/// Engine-level tests for push-then-pull expense sync: cross-device create and
/// delete, offline edit vs. remote delete, repeated pulls, multi-page fetching,
/// and owner/session mismatch producing zero deletions.
final class BidirectionalSyncIntegrationTests: XCTestCase {
    private static let ownerA = "owner-a"
    private static let ownerB = "owner-b"

    private let t0 = Date(timeIntervalSince1970: 1_700_000_000)
    private let t1 = Date(timeIntervalSince1970: 1_700_000_500)

    // MARK: - Repository double

    /// Stands in for the remote table. `fetchAll` enforces the real
    /// repository's contract: the requested owner must match the "session"
    /// owner, otherwise it throws rather than returning an empty snapshot.
    private final class FakeExpenseRepository: ExpenseRepositoring {
        var rows: [UUID: RemoteExpenseRow] = [:]
        var sessionOwnerId: String
        var fetchError: Error?
        private(set) var upsertedIDs: [UUID] = []
        private(set) var deletedIDs: [UUID] = []
        private(set) var fetchCount = 0

        init(sessionOwnerId: String) {
            self.sessionOwnerId = sessionOwnerId
        }

        func upsert(_ expense: Expense) async throws -> String {
            upsertedIDs.append(expense.id)
            rows[expense.id] = RemoteExpenseRow(
                id: expense.id,
                userId: expense.ownerId ?? sessionOwnerId,
                amount: expense.amount,
                categoryName: expense.categoryName,
                note: expense.note,
                date: expense.date,
                createdAt: expense.createdAt,
                updatedAt: Date()
            )
            return expense.id.uuidString
        }

        func delete(id: UUID) async throws {
            deletedIDs.append(id)
            rows[id] = nil
        }

        func fetchAll(ownerId: String) async throws -> [RemoteExpenseRow] {
            fetchCount += 1
            if let fetchError { throw fetchError }
            guard ownerId.lowercased() == sessionOwnerId.lowercased() else {
                throw AuthError.providerUnavailable("Expense owner mismatch")
            }
            return rows.values
                .filter { $0.userId.lowercased() == sessionOwnerId.lowercased() }
                .sorted { $0.id.uuidString < $1.id.uuidString }
        }
    }

    private struct NoOpCategoryRepository: CategoryRepositoring {
        func upsert(_ category: SpendTracker.Category) async throws {}
        func delete(id: UUID) async throws {}
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
        _ repository: FakeExpenseRepository
    ) -> SyncEngine {
        SyncEngine(
            modelContext: context,
            expenseRepository: repository,
            categoryRepository: NoOpCategoryRepository()
        )
    }

    private func row(
        id: UUID = UUID(),
        owner: String,
        amount: Decimal = 10,
        category: String = "Food",
        note: String? = nil,
        updatedAt: Date
    ) -> RemoteExpenseRow {
        RemoteExpenseRow(
            id: id,
            userId: owner,
            amount: amount,
            categoryName: category,
            note: note,
            date: updatedAt,
            createdAt: updatedAt,
            updatedAt: updatedAt
        )
    }

    // MARK: - Cross-device create

    /// The reported bug: an expense entered on the phone never appeared in the
    /// simulator under the same account.
    @MainActor
    func testExpenseCreatedOnAnotherDeviceAppearsLocallyAfterSync() async throws {
        let context = try makeContext()
        let repository = FakeExpenseRepository(sessionOwnerId: Self.ownerA)
        let phoneRow = row(owner: Self.ownerA, amount: 250, category: "Transport", updatedAt: t0)
        repository.rows[phoneRow.id] = phoneRow

        await makeEngine(context, repository).syncExpenses(ownerId: Self.ownerA)

        let stored = try context.fetch(FetchDescriptor<Expense>())
        XCTAssertEqual(stored.count, 1)
        let pulled = try XCTUnwrap(stored.first)
        XCTAssertEqual(pulled.id, phoneRow.id)
        XCTAssertEqual(pulled.amount, 250)
        XCTAssertEqual(pulled.categoryName, "Transport")
        XCTAssertEqual(pulled.ownerId, Self.ownerA)
        XCTAssertEqual(pulled.syncState, .synced)
        XCTAssertEqual(pulled.remoteId, phoneRow.id.uuidString)
    }

    @MainActor
    func testRepeatedSyncsAreIdempotentAndCreateNoDuplicates() async throws {
        let context = try makeContext()
        let repository = FakeExpenseRepository(sessionOwnerId: Self.ownerA)
        let remote = row(owner: Self.ownerA, updatedAt: t0)
        repository.rows[remote.id] = remote
        let engine = makeEngine(context, repository)

        for _ in 0..<3 {
            await engine.syncExpenses(ownerId: Self.ownerA)
        }

        XCTAssertEqual(try context.fetch(FetchDescriptor<Expense>()).count, 1)
        // Nothing changed after the first pull, so there was nothing to re-push.
        XCTAssertTrue(repository.upsertedIDs.isEmpty)
    }

    @MainActor
    func testLocalExpenseIsPushedBeforeTheSnapshotIsFetched() async throws {
        let context = try makeContext()
        let repository = FakeExpenseRepository(sessionOwnerId: Self.ownerA)
        let local = Expense(ownerId: Self.ownerA, amount: 30, categoryName: "Food")
        context.insert(local)
        try context.save()

        await makeEngine(context, repository).syncExpenses(ownerId: Self.ownerA)

        // Pushed first, so the snapshot contained it and absence never deleted it.
        XCTAssertEqual(repository.upsertedIDs, [local.id])
        XCTAssertEqual(try context.fetch(FetchDescriptor<Expense>()).count, 1)
        XCTAssertEqual(local.syncState, .synced)
    }

    @MainActor
    func testNewerRemoteEditOverwritesTheLocalSyncedCopy() async throws {
        let context = try makeContext()
        let repository = FakeExpenseRepository(sessionOwnerId: Self.ownerA)
        let id = UUID()
        let local = Expense(
            id: id, ownerId: Self.ownerA, amount: 10, categoryName: "Food",
            updatedAt: t0, syncState: .synced, remoteId: id.uuidString
        )
        context.insert(local)
        try context.save()
        repository.rows[id] = row(
            id: id, owner: Self.ownerA, amount: 88, category: "Groceries",
            note: "Edited on phone", updatedAt: t1
        )

        await makeEngine(context, repository).syncExpenses(ownerId: Self.ownerA)

        XCTAssertEqual(local.amount, 88)
        XCTAssertEqual(local.categoryName, "Groceries")
        XCTAssertEqual(local.note, "Edited on phone")
        XCTAssertEqual(local.syncState, .synced)
    }

    // MARK: - Cross-device delete

    @MainActor
    func testExpenseDeletedOnAnotherDeviceIsRemovedLocally() async throws {
        let context = try makeContext()
        let repository = FakeExpenseRepository(sessionOwnerId: Self.ownerA)
        let id = UUID()
        context.insert(
            Expense(
                id: id, ownerId: Self.ownerA, amount: 10, categoryName: "Food",
                updatedAt: t0, syncState: .synced, remoteId: id.uuidString
            )
        )
        try context.save()
        // The phone deleted it: it is absent from the complete snapshot.

        await makeEngine(context, repository).syncExpenses(ownerId: Self.ownerA)

        XCTAssertTrue(try context.fetch(FetchDescriptor<Expense>()).isEmpty)
    }

    @MainActor
    func testLocalDeletionPropagatesRemotelyAndDoesNotComeBack() async throws {
        let context = try makeContext()
        let repository = FakeExpenseRepository(sessionOwnerId: Self.ownerA)
        let id = UUID()
        let remote = row(id: id, owner: Self.ownerA, updatedAt: t0)
        repository.rows[id] = remote
        // Locally tombstoned (the user swiped Delete on this device).
        context.insert(
            Expense(
                id: id, ownerId: Self.ownerA, amount: 10, categoryName: "Food",
                updatedAt: t0, syncState: .deleted, remoteId: id.uuidString
            )
        )
        try context.save()
        let engine = makeEngine(context, repository)

        await engine.syncExpenses(ownerId: Self.ownerA)

        XCTAssertEqual(repository.deletedIDs, [id])
        XCTAssertNil(repository.rows[id])
        XCTAssertTrue(try context.fetch(FetchDescriptor<Expense>()).isEmpty)

        // A second sync must not resurrect it.
        await engine.syncExpenses(ownerId: Self.ownerA)
        XCTAssertTrue(try context.fetch(FetchDescriptor<Expense>()).isEmpty)
    }

    @MainActor
    func testNeverPushedLocalRowSurvivesAPullThatCannotKnowAboutItYet() async throws {
        let context = try makeContext()
        let repository = FakeExpenseRepository(sessionOwnerId: Self.ownerA)
        repository.fetchError = AuthError.notConfigured
        let local = Expense(ownerId: Self.ownerA, amount: 10, categoryName: "Food")
        context.insert(local)
        try context.save()

        await makeEngine(context, repository).pullExpenses(ownerId: Self.ownerA)

        XCTAssertEqual(try context.fetch(FetchDescriptor<Expense>()).count, 1)
    }

    // MARK: - Offline edit vs. remote delete

    @MainActor
    func testOfflineEditSurvivesARemoteDeleteAndIsRestoredOnPush() async throws {
        let context = try makeContext()
        let repository = FakeExpenseRepository(sessionOwnerId: Self.ownerA)
        let id = UUID()
        // Edited offline on this device; the other device deleted it remotely.
        let local = Expense(
            id: id, ownerId: Self.ownerA, amount: 55, categoryName: "Food",
            updatedAt: t1, syncState: .dirty, remoteId: id.uuidString
        )
        context.insert(local)
        try context.save()

        await makeEngine(context, repository).syncExpenses(ownerId: Self.ownerA)

        // Deterministic last-writer-wins: the local edit is pushed back up and
        // kept locally rather than being silently discarded.
        XCTAssertEqual(repository.upsertedIDs, [id])
        XCTAssertEqual(try context.fetch(FetchDescriptor<Expense>()).count, 1)
        XCTAssertEqual(local.amount, 55)
        XCTAssertEqual(local.syncState, .synced)
    }

    // MARK: - Failed / partial fetch must never delete

    @MainActor
    func testFailedFetchAbortsThePullWithoutDeletingAnything() async throws {
        let context = try makeContext()
        let repository = FakeExpenseRepository(sessionOwnerId: Self.ownerA)
        repository.fetchError = AuthError.notConfigured
        let id = UUID()
        context.insert(
            Expense(
                id: id, ownerId: Self.ownerA, amount: 10, categoryName: "Food",
                updatedAt: t0, syncState: .synced, remoteId: id.uuidString
            )
        )
        try context.save()

        let changed = await makeEngine(context, repository).pullExpenses(ownerId: Self.ownerA)

        XCTAssertFalse(changed)
        XCTAssertEqual(
            try context.fetch(FetchDescriptor<Expense>()).count, 1,
            "a fetch error must never be read as 'everything was deleted'"
        )
    }

    // MARK: - Owner / session mismatch

    @MainActor
    func testOwnerSessionMismatchIsRejectedWithZeroDeletions() async throws {
        let context = try makeContext()
        // The session belongs to B while we ask for A's data.
        let repository = FakeExpenseRepository(sessionOwnerId: Self.ownerB)
        let id = UUID()
        context.insert(
            Expense(
                id: id, ownerId: Self.ownerA, amount: 10, categoryName: "Food",
                updatedAt: t0, syncState: .synced, remoteId: id.uuidString
            )
        )
        try context.save()

        let changed = await makeEngine(context, repository).pullExpenses(ownerId: Self.ownerA)

        XCTAssertFalse(changed)
        XCTAssertEqual(
            try context.fetch(FetchDescriptor<Expense>()).count, 1,
            "a mismatched session must throw, never return an empty snapshot that erases rows"
        )
    }

    @MainActor
    func testSignedOutSyncFetchesNothingAndDeletesNothing() async throws {
        let context = try makeContext()
        let repository = FakeExpenseRepository(sessionOwnerId: Self.ownerA)
        context.insert(
            Expense(
                ownerId: Self.ownerA, amount: 10, categoryName: "Food",
                updatedAt: t0, syncState: .synced, remoteId: "remote-1"
            )
        )
        try context.save()

        let changed = await makeEngine(context, repository).syncExpenses(ownerId: nil)

        XCTAssertFalse(changed)
        XCTAssertEqual(repository.fetchCount, 0)
        XCTAssertEqual(try context.fetch(FetchDescriptor<Expense>()).count, 1)
    }

    @MainActor
    func testAnotherAccountsLocalRowsAreNeverTouchedByOurPull() async throws {
        let context = try makeContext()
        let repository = FakeExpenseRepository(sessionOwnerId: Self.ownerA)
        let theirs = Expense(
            ownerId: Self.ownerB, amount: 900, categoryName: "Food",
            updatedAt: t0, syncState: .synced, remoteId: "remote-b"
        )
        context.insert(theirs)
        try context.save()

        await makeEngine(context, repository).syncExpenses(ownerId: Self.ownerA)

        let stored = try context.fetch(FetchDescriptor<Expense>())
        XCTAssertEqual(stored.count, 1)
        XCTAssertEqual(stored.first?.ownerId, Self.ownerB)
    }

    @MainActor
    func testRemoteRowBelongingToAnotherAccountIsNotInserted() async throws {
        let context = try makeContext()
        let repository = FakeExpenseRepository(sessionOwnerId: Self.ownerA)
        // Defensive: a row that slipped past RLS must still be ignored.
        let foreign = row(owner: Self.ownerB, updatedAt: t0)
        repository.rows[foreign.id] = foreign

        await makeEngine(context, repository).syncExpenses(ownerId: Self.ownerA)

        XCTAssertTrue(try context.fetch(FetchDescriptor<Expense>()).isEmpty)
    }

    // MARK: - Pagination (snapshot completeness)

    func testPaginationRequestsOrderedRangesUntilAShortPageTerminatesIt() async throws {
        let pageSize = 500
        let total = 1_201
        let all = Array(0..<total)
        var requestedRanges: [(Int, Int)] = []

        let fetched = try await PaginatedSnapshot.fetchAll(pageSize: pageSize) {
            (from: Int, to: Int) -> [Int] in
            requestedRanges.append((from, to))
            guard from < all.count else { return [] }
            return Array(all[from...min(to, all.count - 1)])
        }

        XCTAssertEqual(fetched, all, "every row beyond the first page must be present")
        XCTAssertEqual(requestedRanges.map(\.0), [0, 500, 1000])
        XCTAssertEqual(requestedRanges.map(\.1), [499, 999, 1499])
    }

    func testPaginationStopsOnAnExactMultipleAfterAnEmptyFinalPage() async throws {
        let pageSize = 500
        let all = Array(0..<1_000)
        var pageCount = 0

        let fetched = try await PaginatedSnapshot.fetchAll(pageSize: pageSize) {
            (from: Int, to: Int) -> [Int] in
            pageCount += 1
            guard from < all.count else { return [] }
            return Array(all[from...min(to, all.count - 1)])
        }

        XCTAssertEqual(fetched.count, 1_000)
        XCTAssertEqual(pageCount, 3, "two full pages plus the empty page that proves the end")
    }

    func testPaginationErrorPropagatesInsteadOfReturningAPartialSnapshot() async {
        struct Boom: Error {}

        do {
            _ = try await PaginatedSnapshot.fetchAll(pageSize: 2) {
                (from: Int, _: Int) -> [Int] in
                if from == 0 { return [1, 2] }
                throw Boom()
            }
            XCTFail("a failed page must not yield a partial snapshot")
        } catch {
            XCTAssertTrue(error is Boom)
        }
    }

    func testPaginationThrowsRatherThanLoopingForeverOnAlwaysFullPages() async {
        do {
            _ = try await PaginatedSnapshot.fetchAll(pageSize: 2) { _, _ in [1, 2] }
            XCTFail("a never-terminating backend must fail loudly")
        } catch {
            XCTAssertTrue(error is PaginatedSnapshot.IncompleteSnapshot)
        }
    }

    @MainActor
    func testMultiPageSnapshotIsReconciledWithoutSpuriousDeletions() async throws {
        let context = try makeContext()
        let repository = FakeExpenseRepository(sessionOwnerId: Self.ownerA)
        // More rows than a single page, half of them already local and synced.
        var localIDs: [UUID] = []
        for index in 0..<1_100 {
            let id = UUID()
            let remote = row(id: id, owner: Self.ownerA, amount: Decimal(index + 1), updatedAt: t0)
            repository.rows[id] = remote
            if index.isMultiple(of: 2) {
                localIDs.append(id)
                context.insert(
                    Expense(
                        id: id, ownerId: Self.ownerA, amount: Decimal(index + 1),
                        categoryName: "Food", updatedAt: t0,
                        syncState: .synced, remoteId: id.uuidString
                    )
                )
            }
        }
        try context.save()

        await makeEngine(context, repository).syncExpenses(ownerId: Self.ownerA)

        let stored = try context.fetch(FetchDescriptor<Expense>())
        XCTAssertEqual(stored.count, 1_100, "the full multi-page snapshot must converge")
        XCTAssertEqual(
            Set(stored.map(\.id)).count, 1_100, "no duplicates across page boundaries"
        )
        for id in localIDs {
            XCTAssertTrue(stored.contains { $0.id == id }, "existing synced row was deleted")
        }
    }
}
