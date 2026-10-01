import XCTest
import SwiftData
@testable import SpendTracker

/// Bidirectional custom-category sync, plus the storage fail-loud contract.
///
/// Categories have one rule the other models don't: the eight seeded defaults
/// exist independently on every device with no owner, and must never be pushed,
/// pulled, or deleted by absence.
final class CategorySyncTests: XCTestCase {
    private static let ownerA = "owner-a"
    private static let ownerB = "owner-b"

    private final class FakeCategoryRepository: CategoryRepositoring {
        var rows: [UUID: RemoteCategoryRow] = [:]
        var sessionOwnerId: String
        var fetchError: Error?
        private(set) var upsertedIDs: [UUID] = []
        private(set) var deletedIDs: [UUID] = []
        private(set) var fetchCount = 0
        /// Ranges requested, to prove pagination walks to a short final page.
        private(set) var requestedRanges: [(Int, Int)] = []

        init(sessionOwnerId: String) {
            self.sessionOwnerId = sessionOwnerId
        }

        func upsert(_ category: SpendTracker.Category) async throws {
            upsertedIDs.append(category.id)
            rows[category.id] = RemoteCategoryRow(
                id: category.id,
                userId: category.ownerId ?? sessionOwnerId,
                name: category.name,
                isDefault: category.isDefault,
                sortOrder: category.sortOrder
            )
        }

        func delete(id: UUID) async throws {
            deletedIDs.append(id)
            rows[id] = nil
        }

        func fetchAll(ownerId: String) async throws -> [RemoteCategoryRow] {
            fetchCount += 1
            if let fetchError { throw fetchError }
            guard ownerId.lowercased() == sessionOwnerId.lowercased() else {
                throw AuthError.providerUnavailable("SpendTracker.Category owner mismatch")
            }
            let all = rows.values
                .filter { $0.userId.lowercased() == sessionOwnerId.lowercased() }
                .sorted { $0.id.uuidString < $1.id.uuidString }
            // Mirror the real paginated implementation.
            return try await PaginatedSnapshot.fetchAll(pageSize: 500) { from, to in
                self.requestedRanges.append((from, to))
                guard from < all.count else { return [] }
                return Array(all[from...min(to, all.count - 1)])
            }
        }
    }

    private struct NoOpExpenseRepository: ExpenseRepositoring {
        func upsert(_ expense: Expense) async throws -> String { expense.id.uuidString }
        func delete(id: UUID) async throws {}
        func fetchAll(ownerId: String) async throws -> [RemoteExpenseRow] { [] }
    }

    private struct NoOpFriendRepository: FriendRepositoring {
        func upsert(_ friend: Friend) async throws {}
        func delete(id: UUID) async throws {}
        func fetchAll(ownerId: String) async throws -> [RemoteFriendRow] { [] }
    }

    @MainActor
    private func makeContext() throws -> ModelContext {
        let schema = Schema([Expense.self, SpendTracker.Category.self, UserProfile.self, Friend.self])
        let configuration = ModelConfiguration(schema: schema, isStoredInMemoryOnly: true)
        return ModelContext(try ModelContainer(for: schema, configurations: [configuration]))
    }

    @MainActor
    private func makeEngine(
        _ context: ModelContext,
        _ repository: FakeCategoryRepository
    ) -> SyncEngine {
        SyncEngine(
            modelContext: context,
            expenseRepository: NoOpExpenseRepository(),
            categoryRepository: repository,
            friendRepository: NoOpFriendRepository()
        )
    }

    // MARK: - Cross-device create

    /// The reported gap: a custom category created on the phone never existed
    /// on the simulator.
    @MainActor
    func testCategoryCreatedOnAnotherDeviceAppearsLocally() async throws {
        let context = try makeContext()
        let repository = FakeCategoryRepository(sessionOwnerId: Self.ownerA)
        let remote = RemoteCategoryRow(
            id: UUID(), userId: Self.ownerA, name: "Coffee", sortOrder: 9
        )
        repository.rows[remote.id] = remote

        await makeEngine(context, repository).syncCategories(ownerId: Self.ownerA)

        let stored = try context.fetch(FetchDescriptor<SpendTracker.Category>())
        let pulled = try XCTUnwrap(stored.first { $0.id == remote.id })
        XCTAssertEqual(pulled.name, "Coffee")
        XCTAssertEqual(pulled.sortOrder, 9)
        XCTAssertEqual(pulled.ownerId, Self.ownerA)
        XCTAssertFalse(pulled.isDefault)
        XCTAssertEqual(pulled.syncState, .synced)
    }

    @MainActor
    func testLocalCategoryIsPushedWithItsOwner() async throws {
        let context = try makeContext()
        let repository = FakeCategoryRepository(sessionOwnerId: Self.ownerA)
        let local = SpendTracker.Category(name: "Pets", sortOrder: 4, ownerId: Self.ownerA)
        context.insert(local)
        try context.save()

        await makeEngine(context, repository).syncCategories(ownerId: Self.ownerA)

        XCTAssertEqual(repository.upsertedIDs, [local.id])
        XCTAssertEqual(local.syncState, .synced)
        XCTAssertEqual(repository.rows[local.id]?.userId, Self.ownerA)
    }

    @MainActor
    func testRepeatedSyncsCreateNoDuplicates() async throws {
        let context = try makeContext()
        let repository = FakeCategoryRepository(sessionOwnerId: Self.ownerA)
        let remote = RemoteCategoryRow(id: UUID(), userId: Self.ownerA, name: "Coffee")
        repository.rows[remote.id] = remote
        let engine = makeEngine(context, repository)

        for _ in 0..<3 {
            await engine.syncCategories(ownerId: Self.ownerA)
        }

        XCTAssertEqual(try context.fetch(FetchDescriptor<SpendTracker.Category>()).count, 1)
    }

    /// Both devices created "Coffee" offline: converge instead of showing two.
    @MainActor
    func testSameNameCreatedOnBothDevicesConvergesToOneRow() async throws {
        let context = try makeContext()
        let repository = FakeCategoryRepository(sessionOwnerId: Self.ownerA)
        context.insert(SpendTracker.Category(name: "Coffee", sortOrder: 9, ownerId: Self.ownerA))
        try context.save()
        let remote = RemoteCategoryRow(
            id: UUID(), userId: Self.ownerA, name: "Coffee", sortOrder: 3
        )
        repository.rows[remote.id] = remote

        await makeEngine(context, repository).syncCategories(ownerId: Self.ownerA)

        let stored = try context.fetch(FetchDescriptor<SpendTracker.Category>())
        XCTAssertEqual(stored.filter { $0.name == "Coffee" }.count, 1)
        XCTAssertEqual(stored.first { $0.name == "Coffee" }?.id, remote.id)
    }

    // MARK: - Cross-device update & delete

    @MainActor
    func testRenameOnAnotherDevicePropagates() async throws {
        let context = try makeContext()
        let repository = FakeCategoryRepository(sessionOwnerId: Self.ownerA)
        let id = UUID()
        let local = SpendTracker.Category(
            id: id, name: "Coffee", sortOrder: 3,
            syncState: .synced, remoteId: id.uuidString, ownerId: Self.ownerA
        )
        context.insert(local)
        try context.save()
        repository.rows[id] = RemoteCategoryRow(
            id: id, userId: Self.ownerA, name: "Cafe", sortOrder: 5
        )

        await makeEngine(context, repository).syncCategories(ownerId: Self.ownerA)

        XCTAssertEqual(local.name, "Cafe")
        XCTAssertEqual(local.sortOrder, 5)
    }

    @MainActor
    func testCategoryDeletedOnAnotherDeviceIsRemovedLocally() async throws {
        let context = try makeContext()
        let repository = FakeCategoryRepository(sessionOwnerId: Self.ownerA)
        let id = UUID()
        context.insert(
            SpendTracker.Category(
                id: id, name: "Coffee", syncState: .synced,
                remoteId: id.uuidString, ownerId: Self.ownerA
            )
        )
        try context.save()

        await makeEngine(context, repository).syncCategories(ownerId: Self.ownerA)

        XCTAssertTrue(try context.fetch(FetchDescriptor<SpendTracker.Category>()).isEmpty)
    }

    @MainActor
    func testLocalDeletionTombstonePropagatesRemotely() async throws {
        let context = try makeContext()
        let repository = FakeCategoryRepository(sessionOwnerId: Self.ownerA)
        let id = UUID()
        repository.rows[id] = RemoteCategoryRow(id: id, userId: Self.ownerA, name: "Coffee")
        let local = SpendTracker.Category(
            id: id, name: "Coffee", syncState: .synced,
            remoteId: id.uuidString, ownerId: Self.ownerA
        )
        context.insert(local)
        try context.save()

        ProfileViewModel().deleteCategory(local, context: context, ownerId: Self.ownerA)
        XCTAssertEqual(local.syncState, .deleted, "a synced category becomes a tombstone")

        await makeEngine(context, repository).syncCategories(ownerId: Self.ownerA)

        XCTAssertEqual(repository.deletedIDs, [id])
        XCTAssertTrue(try context.fetch(FetchDescriptor<SpendTracker.Category>()).isEmpty)
    }

    @MainActor
    func testNeverUploadedCategoryIsDeletedOutrightNotTombstoned() async throws {
        let context = try makeContext()
        let local = SpendTracker.Category(name: "Coffee", ownerId: Self.ownerA)
        context.insert(local)
        try context.save()

        ProfileViewModel().deleteCategory(local, context: context, ownerId: Self.ownerA)

        XCTAssertTrue(try context.fetch(FetchDescriptor<SpendTracker.Category>()).isEmpty)
    }

    // MARK: - Seeded defaults

    @MainActor
    func testSeededDefaultsAreNeverPushedOrDeletedByAbsence() async throws {
        let context = try makeContext()
        let repository = FakeCategoryRepository(sessionOwnerId: Self.ownerA)
        for (index, name) in SharedModelContainer.defaultCategoryNames.enumerated() {
            context.insert(
                SpendTracker.Category(name: name, isDefault: true, sortOrder: index, syncState: .synced)
            )
        }
        try context.save()

        await makeEngine(context, repository).syncCategories(ownerId: Self.ownerA)

        XCTAssertEqual(
            try context.fetch(FetchDescriptor<SpendTracker.Category>()).count,
            SharedModelContainer.defaultCategoryNames.count,
            "defaults must survive an empty remote snapshot"
        )
        XCTAssertTrue(repository.upsertedIDs.isEmpty, "defaults must never be uploaded")
    }

    func testDefaultCategoryIsIgnoredByReconciliation() {
        let plan = CategoryReconciliation.plan(
            local: [
                .init(
                    id: UUID(), ownerId: nil, name: "Food",
                    isDefault: true, syncState: .synced, hasRemoteIdentity: true
                )
            ],
            remote: [],
            activeOwnerId: Self.ownerA
        )

        XCTAssertTrue(plan.isEmpty)
    }

    @MainActor
    func testDefaultCannotBeDeletedThroughTheViewModel() throws {
        let schema = Schema([Expense.self, SpendTracker.Category.self, UserProfile.self, Friend.self])
        let configuration = ModelConfiguration(schema: schema, isStoredInMemoryOnly: true)
        let context = ModelContext(try ModelContainer(for: schema, configurations: [configuration]))
        let seeded = SpendTracker.Category(name: "Food", isDefault: true, syncState: .synced)
        context.insert(seeded)
        try context.save()

        ProfileViewModel().deleteCategory(seeded, context: context, ownerId: Self.ownerA)

        XCTAssertEqual(try context.fetch(FetchDescriptor<SpendTracker.Category>()).count, 1)
        XCTAssertEqual(seeded.syncState, .synced)
    }

    // MARK: - Owner isolation

    @MainActor
    func testOwnerSessionMismatchIsRejectedWithZeroDeletions() async throws {
        let context = try makeContext()
        let repository = FakeCategoryRepository(sessionOwnerId: Self.ownerB)
        let id = UUID()
        context.insert(
            SpendTracker.Category(
                id: id, name: "Coffee", syncState: .synced,
                remoteId: id.uuidString, ownerId: Self.ownerA
            )
        )
        try context.save()

        let changed = await makeEngine(context, repository).pullCategories(ownerId: Self.ownerA)

        XCTAssertFalse(changed)
        XCTAssertEqual(try context.fetch(FetchDescriptor<SpendTracker.Category>()).count, 1)
    }

    @MainActor
    func testFailedFetchDeletesNothing() async throws {
        let context = try makeContext()
        let repository = FakeCategoryRepository(sessionOwnerId: Self.ownerA)
        repository.fetchError = AuthError.notConfigured
        let id = UUID()
        context.insert(
            SpendTracker.Category(
                id: id, name: "Coffee", syncState: .synced,
                remoteId: id.uuidString, ownerId: Self.ownerA
            )
        )
        try context.save()

        let changed = await makeEngine(context, repository).pullCategories(ownerId: Self.ownerA)

        XCTAssertFalse(changed)
        XCTAssertEqual(try context.fetch(FetchDescriptor<SpendTracker.Category>()).count, 1)
    }

    @MainActor
    func testAnotherAccountsCategoryIsNeitherPushedNorVisible() async throws {
        let context = try makeContext()
        let repository = FakeCategoryRepository(sessionOwnerId: Self.ownerB)
        let theirs = SpendTracker.Category(name: "A's category", ownerId: Self.ownerA)
        context.insert(theirs)
        try context.save()

        await makeEngine(context, repository).syncCategories(ownerId: Self.ownerB)

        XCTAssertTrue(repository.upsertedIDs.isEmpty, "never upload another account's record")
        XCTAssertTrue(
            ProfileViewModel.visibleCategories(
                try context.fetch(FetchDescriptor<SpendTracker.Category>()), ownerId: Self.ownerB
            ).isEmpty
        )
        XCTAssertEqual(try context.fetch(FetchDescriptor<SpendTracker.Category>()).count, 1)
    }

    @MainActor
    func testSignedOutSyncFetchesNothingAndDeletesNothing() async throws {
        let context = try makeContext()
        let repository = FakeCategoryRepository(sessionOwnerId: Self.ownerA)
        context.insert(
            SpendTracker.Category(name: "Coffee", syncState: .synced, ownerId: Self.ownerA)
        )
        try context.save()

        let changed = await makeEngine(context, repository).syncCategories(ownerId: nil)

        XCTAssertFalse(changed)
        XCTAssertEqual(repository.fetchCount, 0)
        XCTAssertEqual(try context.fetch(FetchDescriptor<SpendTracker.Category>()).count, 1)
    }

    @MainActor
    func testVisibleCategoriesShowsDefaultsPlusOwnCustomOnly() throws {
        let defaults = SpendTracker.Category(name: "Food", isDefault: true, syncState: .synced)
        let mine = SpendTracker.Category(name: "Coffee", ownerId: Self.ownerA)
        let theirs = SpendTracker.Category(name: "Theirs", ownerId: Self.ownerB)
        let tombstoned = SpendTracker.Category(
            name: "Gone", syncState: .deleted, ownerId: Self.ownerA
        )

        let visible = ProfileViewModel.visibleCategories(
            [defaults, mine, theirs, tombstoned], ownerId: Self.ownerA
        )

        XCTAssertEqual(visible.map(\.name).sorted(), ["Coffee", "Food"])
    }

    // MARK: - Pagination

    @MainActor
    func testMultiPageCategorySnapshotIsReconciledWithoutSpuriousDeletions() async throws {
        let context = try makeContext()
        let repository = FakeCategoryRepository(sessionOwnerId: Self.ownerA)
        var localIDs: [UUID] = []
        for index in 0..<1_100 {
            let id = UUID()
            repository.rows[id] = RemoteCategoryRow(
                id: id, userId: Self.ownerA, name: "C\(index)", sortOrder: index
            )
            if index.isMultiple(of: 2) {
                localIDs.append(id)
                context.insert(
                    SpendTracker.Category(
                        id: id, name: "C\(index)", sortOrder: index,
                        syncState: .synced, remoteId: id.uuidString, ownerId: Self.ownerA
                    )
                )
            }
        }
        try context.save()

        await makeEngine(context, repository).syncCategories(ownerId: Self.ownerA)

        let stored = try context.fetch(FetchDescriptor<SpendTracker.Category>())
        XCTAssertEqual(stored.count, 1_100)
        XCTAssertEqual(Set(stored.map(\.id)).count, 1_100, "no duplicates across pages")
        for id in localIDs {
            XCTAssertTrue(stored.contains { $0.id == id }, "an existing synced row was deleted")
        }
        XCTAssertGreaterThan(repository.requestedRanges.count, 1, "more than one page requested")
    }

    func testRemoteCategoryRowDecodesPostgRESTShape() throws {
        let id = UUID()
        let json = """
        [
          {
            "id": "\(id.uuidString.lowercased())",
            "user_id": "owner-a",
            "name": "Coffee",
            "is_default": false,
            "sort_order": 7
          }
        ]
        """.data(using: .utf8)!

        let rows = try JSONDecoder().decode([RemoteCategoryRow].self, from: json)

        XCTAssertEqual(rows.count, 1)
        XCTAssertEqual(rows[0].id, id)
        XCTAssertEqual(rows[0].name, "Coffee")
        XCTAssertEqual(rows[0].sortOrder, 7)
        XCTAssertFalse(rows[0].isDefault)
    }

    // MARK: - Fail-loud shared storage

    /// The App Group fallback must be reported, not silent. In a correctly
    /// signed test host the container resolves, so `configurationError` is nil;
    /// the error's own messaging is asserted directly.
    func testStorageConfigurationErrorMessageIsUserFacingAndNamesTheCause() {
        let error = SharedModelContainer.ConfigurationError.appGroupUnavailable(
            identifier: AppGroupConstants.appGroupID
        )

        XCTAssertTrue(error.message.contains(AppGroupConstants.appGroupID))
        XCTAssertTrue(
            error.message.lowercased().contains("saving is disabled"),
            "the user must be told writes are blocked, not left with silent divergence"
        )
        XCTAssertFalse(error.developerHint.isEmpty)
    }

    func testStoreOpenFailureSurfacesTheUnderlyingReason() {
        let error = SharedModelContainer.ConfigurationError.storeOpenFailed(
            identifier: "group.test", underlying: "disk full"
        )

        XCTAssertTrue(error.message.contains("group.test"))
        XCTAssertTrue(error.message.contains("disk full"))
    }

    /// The signed test host provisions the App Group, so the shared container
    /// must be healthy — this fails loudly if a build drops the entitlement.
    func testSharedContainerIsBackedByTheAppGroupInASignedHost() {
        _ = SharedModelContainer.shared
        XCTAssertNil(
            SharedModelContainer.configurationError,
            "the entitlement-preserving test host must resolve the App Group container"
        )
    }
}
