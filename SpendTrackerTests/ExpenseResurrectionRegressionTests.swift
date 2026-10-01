import XCTest
import SwiftData
@testable import SpendTracker

/// Regression tests for the reported bug: a transaction deleted on the phone
/// reappeared in the simulator. The resurrection loop was push-before-pull —
/// the stale local row was re-uploaded, which re-created it remotely, and the
/// following snapshot then "confirmed" it forever.
final class ExpenseResurrectionRegressionTests: XCTestCase {
    private static let owner = "owner-a"
    private let t0 = Date(timeIntervalSince1970: 1_700_000_000)

    /// Records every call so ordering (pull before push) can be asserted.
    private final class TracingExpenseRepository: ExpenseRepositoring {
        enum Call: Equatable {
            case fetch
            case upsert(UUID)
            case delete(UUID)
        }

        var rows: [UUID: RemoteExpenseRow] = [:]
        var sessionOwnerId: String
        private(set) var calls: [Call] = []

        init(sessionOwnerId: String) {
            self.sessionOwnerId = sessionOwnerId
        }

        func upsert(_ expense: Expense) async throws -> String {
            calls.append(.upsert(expense.id))
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
            calls.append(.delete(id))
            rows[id] = nil
        }

        func fetchAll(ownerId: String) async throws -> [RemoteExpenseRow] {
            calls.append(.fetch)
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
        func fetchAll(ownerId: String) async throws -> [RemoteCategoryRow] { [] }
    }

    private struct NoOpFriendRepository: FriendRepositoring {
        func upsert(_ friend: Friend) async throws {}
        func delete(id: UUID) async throws {}
        func fetchAll(ownerId: String) async throws -> [RemoteFriendRow] { [] }
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
        _ repository: TracingExpenseRepository
    ) -> SyncEngine {
        SyncEngine(
            modelContext: context,
            expenseRepository: repository,
            categoryRepository: NoOpCategoryRepository(),
            friendRepository: NoOpFriendRepository()
        )
    }

    // MARK: - The reported bug

    /// Delete on phone → the simulator's stale `.synced` row must disappear and
    /// must NOT be re-uploaded.
    @MainActor
    func testDeletedOnPhoneRemovesStaleLocalRowAndDoesNotReUpload() async throws {
        let context = try makeContext()
        let repository = TracingExpenseRepository(sessionOwnerId: Self.owner)
        let id = UUID()
        // The simulator still holds the row from an earlier sync, with a remote
        // identity; the phone has since deleted it, so it is absent remotely.
        let stale = Expense(
            id: id, ownerId: Self.owner, amount: 250, categoryName: "Food",
            date: t0, updatedAt: t0, syncState: .synced, remoteId: id.uuidString
        )
        context.insert(stale)
        try context.save()

        await makeEngine(context, repository).syncExpenses(ownerId: Self.owner)

        XCTAssertTrue(
            try context.fetch(FetchDescriptor<Expense>()).isEmpty,
            "a transaction deleted on another device must disappear locally"
        )
        XCTAssertFalse(
            repository.calls.contains(.upsert(id)),
            "the stale row must never be re-uploaded — that is what resurrected it"
        )
        XCTAssertNil(repository.rows[id], "the backend row must stay deleted")
    }

    /// The resurrection loop: repeated syncs must not bring it back either.
    @MainActor
    func testStaleRowStaysDeletedAcrossRepeatedSyncs() async throws {
        let context = try makeContext()
        let repository = TracingExpenseRepository(sessionOwnerId: Self.owner)
        let id = UUID()
        context.insert(
            Expense(
                id: id, ownerId: Self.owner, amount: 99, categoryName: "Food",
                date: t0, updatedAt: t0, syncState: .synced, remoteId: id.uuidString
            )
        )
        try context.save()
        let engine = makeEngine(context, repository)

        for _ in 0..<3 {
            await engine.syncExpenses(ownerId: Self.owner)
        }

        XCTAssertTrue(try context.fetch(FetchDescriptor<Expense>()).isEmpty)
        XCTAssertTrue(repository.rows.isEmpty)
    }

    /// A row deleted remotely while it was edited locally and not yet uploaded
    /// still carries a remote identity, so remote absence wins — it must not be
    /// resurrected by the pending edit.
    @MainActor
    func testDirtyRowWithRemoteIdentityIsDeletedByRemoteAbsence() async throws {
        let context = try makeContext()
        let repository = TracingExpenseRepository(sessionOwnerId: Self.owner)
        let id = UUID()
        context.insert(
            Expense(
                id: id, ownerId: Self.owner, amount: 500, categoryName: "Food",
                date: t0, updatedAt: t0, syncState: .dirty, remoteId: id.uuidString
            )
        )
        try context.save()

        await makeEngine(context, repository).syncExpenses(ownerId: Self.owner)

        XCTAssertTrue(try context.fetch(FetchDescriptor<Expense>()).isEmpty)
        XCTAssertFalse(repository.calls.contains(.upsert(id)))
    }

    // MARK: - What must NOT break

    /// A brand-new local row has never been uploaded, so remote absence says
    /// nothing about it: it must survive and be pushed.
    @MainActor
    func testNeverUploadedLocalRowSurvivesAndIsPushed() async throws {
        let context = try makeContext()
        let repository = TracingExpenseRepository(sessionOwnerId: Self.owner)
        let local = Expense(
            ownerId: Self.owner, amount: 75, categoryName: "Food", date: t0
        )
        context.insert(local)
        try context.save()

        await makeEngine(context, repository).syncExpenses(ownerId: Self.owner)

        XCTAssertEqual(try context.fetch(FetchDescriptor<Expense>()).count, 1)
        XCTAssertEqual(local.syncState, .synced)
        XCTAssertNotNil(repository.rows[local.id], "a new local expense must reach the backend")
    }

    /// A local delete must still propagate: the tombstone pushes after the pull.
    @MainActor
    func testLocalTombstoneStillPushesAfterThePull() async throws {
        let context = try makeContext()
        let repository = TracingExpenseRepository(sessionOwnerId: Self.owner)
        let id = UUID()
        repository.rows[id] = RemoteExpenseRow(
            id: id, userId: Self.owner, amount: 10, categoryName: "Food",
            note: nil, date: t0, createdAt: t0, updatedAt: t0
        )
        context.insert(
            Expense(
                id: id, ownerId: Self.owner, amount: 10, categoryName: "Food",
                date: t0, updatedAt: t0, syncState: .deleted, remoteId: id.uuidString
            )
        )
        try context.save()

        await makeEngine(context, repository).syncExpenses(ownerId: Self.owner)

        XCTAssertTrue(repository.calls.contains(.delete(id)), "the tombstone must still push")
        XCTAssertNil(repository.rows[id])
        XCTAssertTrue(try context.fetch(FetchDescriptor<Expense>()).isEmpty)
    }

    /// Ordering is the fix. Assert it directly so a future refactor that
    /// reorders the calls fails here instead of resurrecting rows in the field.
    @MainActor
    func testSnapshotIsFetchedBeforeAnyUpload() async throws {
        let context = try makeContext()
        let repository = TracingExpenseRepository(sessionOwnerId: Self.owner)
        context.insert(
            Expense(ownerId: Self.owner, amount: 20, categoryName: "Food", date: t0)
        )
        try context.save()

        await makeEngine(context, repository).syncExpenses(ownerId: Self.owner)

        let firstFetch = try XCTUnwrap(repository.calls.firstIndex(of: .fetch))
        let firstWrite = repository.calls.firstIndex {
            if case .upsert = $0 { return true }
            if case .delete = $0 { return true }
            return false
        }
        if let firstWrite {
            XCTAssertLessThan(firstFetch, firstWrite, "pull must precede push")
        }
    }

    /// A failed fetch must not delete anything, even now that more states are
    /// eligible for absence-deletion.
    @MainActor
    func testFetchFailureDeletesNothing() async throws {
        let context = try makeContext()
        // Session owner differs, so fetchAll throws rather than returning empty.
        let repository = TracingExpenseRepository(sessionOwnerId: "someone-else")
        let id = UUID()
        context.insert(
            Expense(
                id: id, ownerId: Self.owner, amount: 42, categoryName: "Food",
                date: t0, updatedAt: t0, syncState: .synced, remoteId: id.uuidString
            )
        )
        try context.save()

        let changed = await makeEngine(context, repository).pullExpenses(ownerId: Self.owner)

        XCTAssertFalse(changed)
        XCTAssertEqual(try context.fetch(FetchDescriptor<Expense>()).count, 1)
    }

    // MARK: - Pure rule coverage

    func testAbsenceDeletesOnlyPreviouslyUploadedRows() {
        let syncedRow = UUID()
        let dirtyWithRemote = UUID()
        let tombstoneWithRemote = UUID()
        let neverUploaded = UUID()
        let dirtyNeverUploaded = UUID()

        let plan = ExpenseReconciliation.plan(
            local: [
                .init(
                    id: syncedRow, ownerId: Self.owner, updatedAt: t0,
                    syncState: .synced, hasRemoteIdentity: true
                ),
                .init(
                    id: dirtyWithRemote, ownerId: Self.owner, updatedAt: t0,
                    syncState: .dirty, hasRemoteIdentity: true
                ),
                .init(
                    id: tombstoneWithRemote, ownerId: Self.owner, updatedAt: t0,
                    syncState: .deleted, hasRemoteIdentity: true
                ),
                .init(
                    id: neverUploaded, ownerId: Self.owner, updatedAt: t0,
                    syncState: .local, hasRemoteIdentity: false
                ),
                .init(
                    id: dirtyNeverUploaded, ownerId: Self.owner, updatedAt: t0,
                    syncState: .dirty, hasRemoteIdentity: false
                ),
            ],
            remote: [],
            activeOwnerId: Self.owner
        )

        XCTAssertEqual(
            Set(plan.deletions),
            Set([syncedRow, dirtyWithRemote, tombstoneWithRemote])
        )
        XCTAssertFalse(plan.deletions.contains(neverUploaded))
        XCTAssertFalse(plan.deletions.contains(dirtyNeverUploaded))
    }
}
