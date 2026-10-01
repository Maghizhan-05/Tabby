import XCTest
import SwiftData
@testable import SpendTracker

final class FriendFeatureTests: XCTestCase {

    @MainActor
    func testFriendPersistsBalancesAndComputesNetBalance() throws {
        let schema = Schema([Expense.self, Category.self, UserProfile.self, Friend.self])
        let config = ModelConfiguration(schema: schema, isStoredInMemoryOnly: true)
        let container = try ModelContainer(for: schema, configurations: [config])
        let context = ModelContext(container)
        let friend = Friend(name: "  Maya  ", theyOweUs: 32.50, weOweThem: 12.25)

        context.insert(friend)
        try context.save()

        let stored = try XCTUnwrap(context.fetch(FetchDescriptor<Friend>()).first)
        XCTAssertEqual(stored.name, "Maya")
        XCTAssertEqual(stored.theyOweUs, 32.50)
        XCTAssertEqual(stored.weOweThem, 12.25)
        XCTAssertEqual(stored.netBalance, 20.25)
        XCTAssertEqual(stored.syncState, .local)
    }

    func testFriendPayloadUsesLocalUUIDAsCloudPrimaryKeyWithoutRemoteID() throws {
        let payload = FriendUpsertPayload(
            id: "3F2504E0-4F89-41D3-9A0C-0305E82C3301",
            user_id: "user-id",
            name: "Maya",
            they_owe_us: "32.5",
            we_owe_them: "12.25",
            created_at: "2026-10-01T10:00:00Z",
            updated_at: "2026-10-01T10:00:00Z"
        )

        let object = try XCTUnwrap(
            JSONSerialization.jsonObject(with: JSONEncoder().encode(payload)) as? [String: Any]
        )
        XCTAssertEqual(object["id"] as? String, "3F2504E0-4F89-41D3-9A0C-0305E82C3301")
        XCTAssertNil(object["remote_id"])
    }

    @MainActor
    func testFailedFriendPushLeavesFriendUnsynced() async throws {
        let schema = Schema([Expense.self, Category.self, UserProfile.self, Friend.self])
        let config = ModelConfiguration(schema: schema, isStoredInMemoryOnly: true)
        let container = try ModelContainer(for: schema, configurations: [config])
        let context = ModelContext(container)
        let ownerId = "owner-id"
        let friend = Friend(name: "Maya", ownerId: ownerId, theyOweUs: 10, weOweThem: 0)
        context.insert(friend)
        try context.save()

        let engine = SyncEngine(
            modelContext: context,
            expenseRepository: NoOpExpenseRepository(),
            categoryRepository: NoOpCategoryRepository(),
            friendRepository: FailingFriendRepository()
        )
        await engine.pushUnsyncedFriends(ownerId: ownerId)

        let stored = try XCTUnwrap(context.fetch(FetchDescriptor<Friend>()).first)
        XCTAssertEqual(stored.syncState, .local)
    }

    @MainActor
    func testAggregateNetCombinesOnlyActiveFriendBalances() {
        let friends = [
            Friend(name: "Maya", theyOweUs: 40, weOweThem: 10),
            Friend(name: "Leo", theyOweUs: 5, weOweThem: 20),
            Friend(name: "Deleted", theyOweUs: 100, weOweThem: 0, syncState: .deleted),
        ]

        XCTAssertEqual(FriendsViewModel.aggregateNet(of: friends), 15)
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
}

private struct FailingFriendRepository: FriendRepositoring {
    struct Failure: Error {}
    func upsert(_ friend: Friend) async throws { throw Failure() }
    func delete(id: UUID) async throws {}
}
