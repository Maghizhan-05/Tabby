import XCTest
import SwiftData
@testable import SpendTracker

/// Covers the three confirmed defects: cross-account expense exposure/upload,
/// missing remote deletion, and the overlapping-sync race.
final class ExpenseOwnershipAndSyncTests: XCTestCase {
    private static let ownerA = "owner-a"
    private static let ownerB = "owner-b"

    // MARK: - Doubles

    private final class RecordingExpenseRepository: ExpenseRepositoring {
        private(set) var upsertedIDs: [UUID] = []
        private(set) var deletedIDs: [UUID] = []
        var deleteError: Error?

        func upsert(_ expense: Expense) async throws -> String {
            upsertedIDs.append(expense.id)
            return expense.id.uuidString
        }

        func delete(id: UUID) async throws {
            if let deleteError { throw deleteError }
            deletedIDs.append(id)
        }

        func fetchAll(ownerId: String) async throws -> [RemoteExpenseRow] { [] }
    }

    /// Suspends the first upsert until the test releases it, so an edit can
    /// land while an upload is in flight.
    private final class SuspendingExpenseRepository: ExpenseRepositoring {
        private(set) var upsertCount = 0
        private var continuation: CheckedContinuation<Void, Never>?
        private var isSuspending = true

        func upsert(_ expense: Expense) async throws -> String {
            upsertCount += 1
            if isSuspending {
                isSuspending = false
                await withCheckedContinuation { continuation = $0 }
            }
            return expense.id.uuidString
        }

        func delete(id: UUID) async throws {}

        func fetchAll(ownerId: String) async throws -> [RemoteExpenseRow] { [] }

        func release() {
            continuation?.resume()
            continuation = nil
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

    // MARK: - 1. Cross-account exposure

    func testVisibleExpensesExcludeOtherAccountsAndTombstones() {
        let mine = Expense(ownerId: Self.ownerA, amount: 10, categoryName: "Food")
        let theirs = Expense(ownerId: Self.ownerB, amount: 20, categoryName: "Food")
        let legacy = Expense(amount: 30, categoryName: "Food")
        let deleted = Expense(
            ownerId: Self.ownerA,
            amount: 40,
            categoryName: "Food",
            syncState: .deleted
        )

        let visible = ExpenseOwnership.visibleExpenses(
            [mine, theirs, legacy, deleted],
            activeOwnerId: Self.ownerA
        )

        // Legacy (unclaimed) rows stay visible so pre-migration data isn't lost.
        XCTAssertEqual(visible.map(\.amount), [10, 30])
    }

    func testSignedOutSessionSeesNoExpenses() {
        let mine = Expense(ownerId: Self.ownerA, amount: 10, categoryName: "Food")
        let legacy = Expense(amount: 30, categoryName: "Food")

        XCTAssertTrue(ExpenseOwnership.visibleExpenses([mine, legacy], activeOwnerId: nil).isEmpty)
        XCTAssertTrue(ExpenseOwnership.visibleExpenses([mine, legacy], activeOwnerId: "  ").isEmpty)
    }

    func testOwnerIdComparisonIgnoresCaseAndWhitespace() {
        XCTAssertTrue(
            ExpenseOwnership.isAccessible(recordOwnerId: "Owner-A", activeOwnerId: " owner-a ")
        )
        XCTAssertFalse(
            ExpenseOwnership.isAccessible(recordOwnerId: Self.ownerB, activeOwnerId: Self.ownerA)
        )
    }

    func testResolvedOwnerClaimsLegacyRowsButNeverRestampsAnotherAccount() {
        XCTAssertEqual(
            ExpenseOwnership.resolvedOwnerId(recordOwnerId: nil, activeOwnerId: Self.ownerA),
            Self.ownerA
        )
        XCTAssertEqual(
            ExpenseOwnership.resolvedOwnerId(recordOwnerId: Self.ownerA, activeOwnerId: Self.ownerA),
            Self.ownerA
        )
        XCTAssertNil(
            ExpenseOwnership.resolvedOwnerId(recordOwnerId: Self.ownerB, activeOwnerId: Self.ownerA)
        )
    }

    @MainActor
    func testPushUploadsOnlyActiveOwnersExpensesAndNeverRestampsThem() async throws {
        let context = try makeContext()
        let mine = Expense(ownerId: Self.ownerA, amount: 10, categoryName: "Food")
        let theirs = Expense(ownerId: Self.ownerB, amount: 20, categoryName: "Food")
        context.insert(mine)
        context.insert(theirs)
        try context.save()

        let repository = RecordingExpenseRepository()
        let engine = SyncEngine(
            modelContext: context,
            expenseRepository: repository,
            categoryRepository: NoOpCategoryRepository()
        )

        await engine.pushUnsyncedExpenses(ownerId: Self.ownerA)

        XCTAssertEqual(repository.upsertedIDs, [mine.id])
        XCTAssertEqual(mine.syncState, .synced)
        // The other account's record is untouched: not uploaded, not re-stamped.
        XCTAssertEqual(theirs.syncState, .local)
        XCTAssertEqual(theirs.ownerId, Self.ownerB)
    }

    @MainActor
    func testSignedOutPushUploadsNothing() async throws {
        let context = try makeContext()
        context.insert(Expense(ownerId: Self.ownerA, amount: 10, categoryName: "Food"))
        try context.save()
        let repository = RecordingExpenseRepository()
        let engine = SyncEngine(
            modelContext: context,
            expenseRepository: repository,
            categoryRepository: NoOpCategoryRepository()
        )

        await engine.pushUnsyncedExpenses(ownerId: nil)

        XCTAssertTrue(repository.upsertedIDs.isEmpty)
    }

    @MainActor
    func testLegacyExpenseIsClaimedByTheSignedInAccountOnPush() async throws {
        let context = try makeContext()
        let legacy = Expense(amount: 10, categoryName: "Food")
        context.insert(legacy)
        try context.save()
        let repository = RecordingExpenseRepository()
        let engine = SyncEngine(
            modelContext: context,
            expenseRepository: repository,
            categoryRepository: NoOpCategoryRepository()
        )

        await engine.pushUnsyncedExpenses(ownerId: Self.ownerA)

        XCTAssertEqual(legacy.ownerId, Self.ownerA)
        XCTAssertEqual(repository.upsertedIDs, [legacy.id])
    }

    @MainActor
    func testEditingAnotherAccountsExpenseIsRefused() throws {
        let context = try makeContext()
        let theirs = Expense(ownerId: Self.ownerB, amount: 10, categoryName: "Food")
        context.insert(theirs)
        try context.save()

        let viewModel = ExpenseEditViewModel(expense: theirs)
        viewModel.amountText = "99"

        XCTAssertFalse(viewModel.save(categories: [], context: context, ownerId: Self.ownerA))
        XCTAssertEqual(viewModel.notice, "This spend belongs to another account.")
        XCTAssertEqual(theirs.amount, 10)
    }

    @MainActor
    func testQuickEntryStampsOwnerAndRefusesWhenSignedOut() throws {
        let context = try makeContext()
        let viewModel = QuickEntryViewModel()
        viewModel.amountText = "12"
        viewModel.categoryQuery = "Food"

        XCTAssertNil(viewModel.submit(categories: [], context: context, ownerId: nil))
        XCTAssertEqual(viewModel.notice, "Sign in to save this spend.")

        let saved = try XCTUnwrap(
            viewModel.submit(categories: [], context: context, ownerId: Self.ownerA)
        )
        XCTAssertEqual(saved.ownerId, Self.ownerA)
    }

    func testWidgetSnapshotShowsOnlyTheActiveOwnersSpending() throws {
        let storeURL = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent("OwnershipWidgetTests-\(UUID().uuidString).store")
        addTeardownBlock { try? FileManager.default.removeItem(at: storeURL) }
        let schema = Schema([Expense.self, Category.self, UserProfile.self])
        let container = try ModelContainer(
            for: schema,
            configurations: [ModelConfiguration(schema: schema, url: storeURL)]
        )
        let context = ModelContext(container)
        let now = Date()
        context.insert(Expense(ownerId: Self.ownerA, amount: 100, categoryName: "Food", date: now))
        context.insert(Expense(ownerId: Self.ownerB, amount: 900, categoryName: "Food", date: now))
        try context.save()

        let mine = WidgetDataProvider.snapshot(
            from: container, mode: .daily, now: now, ownerId: Self.ownerA
        )
        let signedOut = WidgetDataProvider.snapshot(
            from: container, mode: .daily, now: now, ownerId: nil
        )

        XCTAssertEqual(mine.total, 100)
        XCTAssertEqual(signedOut.total, 0, "a signed-out widget must not show anyone's spending")
        XCTAssertTrue(signedOut.slices.isEmpty)
    }

    // MARK: - 2. Remote deletion

    func testDeletionPlanTombstonesAnythingThatMayExistRemotely() {
        let neverPushed = Expense(ownerId: Self.ownerA, amount: 10, categoryName: "Food")
        let synced = Expense(
            ownerId: Self.ownerA, amount: 10, categoryName: "Food",
            syncState: .synced, remoteId: "remote-1"
        )
        let dirty = Expense(
            ownerId: Self.ownerA, amount: 10, categoryName: "Food", syncState: .dirty
        )

        XCTAssertEqual(ExpenseOwnership.deletionPlan(for: neverPushed), .removeLocally)
        XCTAssertEqual(ExpenseOwnership.deletionPlan(for: synced), .tombstone)
        XCTAssertEqual(ExpenseOwnership.deletionPlan(for: dirty), .tombstone)
    }

    @MainActor
    func testTombstonedExpenseIsDeletedRemotelyThenRemovedLocally() async throws {
        let context = try makeContext()
        let expense = Expense(
            ownerId: Self.ownerA, amount: 10, categoryName: "Food",
            syncState: .deleted, remoteId: "remote-1"
        )
        context.insert(expense)
        try context.save()
        let repository = RecordingExpenseRepository()
        let engine = SyncEngine(
            modelContext: context,
            expenseRepository: repository,
            categoryRepository: NoOpCategoryRepository()
        )

        await engine.pushUnsyncedExpenses(ownerId: Self.ownerA)

        XCTAssertEqual(repository.deletedIDs.count, 1)
        XCTAssertTrue(repository.upsertedIDs.isEmpty, "a tombstone must never be re-upserted")
        XCTAssertTrue(try context.fetch(FetchDescriptor<Expense>()).isEmpty)
    }

    @MainActor
    func testFailedRemoteDeleteKeepsTombstoneForRetryAndSucceedsOnRetry() async throws {
        struct Failure: Error {}
        let context = try makeContext()
        let expense = Expense(
            ownerId: Self.ownerA, amount: 10, categoryName: "Food",
            syncState: .deleted, remoteId: "remote-1"
        )
        context.insert(expense)
        try context.save()
        let repository = RecordingExpenseRepository()
        repository.deleteError = Failure()
        let engine = SyncEngine(
            modelContext: context,
            expenseRepository: repository,
            categoryRepository: NoOpCategoryRepository()
        )

        await engine.pushUnsyncedExpenses(ownerId: Self.ownerA)

        let remaining = try context.fetch(FetchDescriptor<Expense>())
        XCTAssertEqual(remaining.count, 1, "a failed remote delete must not drop the local record")
        XCTAssertEqual(remaining.first?.syncState, .deleted)

        repository.deleteError = nil
        await engine.pushUnsyncedExpenses(ownerId: Self.ownerA)

        XCTAssertEqual(repository.deletedIDs.count, 1)
        XCTAssertTrue(try context.fetch(FetchDescriptor<Expense>()).isEmpty)
    }

    @MainActor
    func testAnotherAccountsTombstoneIsNotDeletedUnderThisSession() async throws {
        let context = try makeContext()
        let theirs = Expense(
            ownerId: Self.ownerB, amount: 10, categoryName: "Food",
            syncState: .deleted, remoteId: "remote-1"
        )
        context.insert(theirs)
        try context.save()
        let repository = RecordingExpenseRepository()
        let engine = SyncEngine(
            modelContext: context,
            expenseRepository: repository,
            categoryRepository: NoOpCategoryRepository()
        )

        await engine.pushUnsyncedExpenses(ownerId: Self.ownerA)

        XCTAssertTrue(repository.deletedIDs.isEmpty)
        XCTAssertEqual(try context.fetch(FetchDescriptor<Expense>()).count, 1)
    }

    // MARK: - 3. Overlapping-sync race

    @MainActor
    func testEditDuringInFlightUploadIsNotMarkedSyncedAndIsRetried() async throws {
        let context = try makeContext()
        let expense = Expense(
            ownerId: Self.ownerA, amount: 10, categoryName: "Food", syncState: .dirty
        )
        context.insert(expense)
        try context.save()
        let repository = SuspendingExpenseRepository()
        let engine = SyncEngine(
            modelContext: context,
            expenseRepository: repository,
            categoryRepository: NoOpCategoryRepository()
        )

        let push = Task { await engine.pushUnsyncedExpenses(ownerId: Self.ownerA) }
        // Let the push reach the suspended upsert, then edit the same record.
        try await Task.sleep(nanoseconds: 50_000_000)
        let viewModel = ExpenseEditViewModel(
            expense: expense,
            reloadWidgetTimelines: {},
            pushExpenses: { _, _ in }
        )
        viewModel.amountText = "99"
        XCTAssertTrue(viewModel.save(categories: [], context: context, ownerId: Self.ownerA))
        repository.release()
        await push.value

        XCTAssertEqual(expense.amount, 99)
        XCTAssertEqual(
            expense.syncState, .dirty,
            "an edit made during an in-flight upload must stay dirty, not be marked synced"
        )

        // The retry uploads the current revision and only then marks it synced.
        let retryRepository = RecordingExpenseRepository()
        let retryEngine = SyncEngine(
            modelContext: context,
            expenseRepository: retryRepository,
            categoryRepository: NoOpCategoryRepository()
        )
        await retryEngine.pushUnsyncedExpenses(ownerId: Self.ownerA)
        XCTAssertEqual(retryRepository.upsertedIDs, [expense.id])
        XCTAssertEqual(expense.syncState, .synced)
    }

    @MainActor
    func testEachEditBumpsTheRevision() throws {
        let context = try makeContext()
        let expense = Expense(ownerId: Self.ownerA, amount: 10, categoryName: "Food")
        context.insert(expense)
        try context.save()
        let viewModel = ExpenseEditViewModel(
            expense: expense,
            reloadWidgetTimelines: {},
            pushExpenses: { _, _ in }
        )

        viewModel.amountText = "11"
        XCTAssertTrue(viewModel.save(categories: [], context: context, ownerId: Self.ownerA))
        viewModel.amountText = "12"
        XCTAssertTrue(viewModel.save(categories: [], context: context, ownerId: Self.ownerA))

        XCTAssertEqual(expense.revision, 2)
    }

    @MainActor
    func testOverlappingPushesAreSerializedAndUploadEachRecordOnce() async throws {
        let context = try makeContext()
        let expense = Expense(
            ownerId: Self.ownerA, amount: 10, categoryName: "Food", syncState: .dirty
        )
        context.insert(expense)
        try context.save()
        let repository = SuspendingExpenseRepository()
        let engine = SyncEngine(
            modelContext: context,
            expenseRepository: repository,
            categoryRepository: NoOpCategoryRepository()
        )

        let first = Task { await engine.pushUnsyncedExpenses(ownerId: Self.ownerA) }
        try await Task.sleep(nanoseconds: 50_000_000)
        let second = Task { await engine.pushUnsyncedExpenses(ownerId: Self.ownerA) }
        try await Task.sleep(nanoseconds: 50_000_000)
        XCTAssertEqual(
            repository.upsertCount, 1,
            "the second push must wait instead of interleaving a duplicate upsert"
        )

        repository.release()
        await first.value
        await second.value

        // The second push found the record already synced, so it uploaded nothing more.
        XCTAssertEqual(repository.upsertCount, 1)
        XCTAssertEqual(expense.syncState, .synced)
    }
}
