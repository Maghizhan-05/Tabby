import XCTest
@testable import SpendTracker

/// Pure merge-rule tests for the bidirectional expense sync. No SwiftData or
/// network involved — only `ExpenseReconciliation.plan`.
final class ExpenseReconciliationTests: XCTestCase {
    private let ownerA = "owner-a"
    private let ownerB = "owner-b"

    private let t0 = Date(timeIntervalSince1970: 1_700_000_000)
    private let t1 = Date(timeIntervalSince1970: 1_700_000_500)

    private func row(
        id: UUID,
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

    private func local(
        id: UUID,
        owner: String?,
        updatedAt: Date,
        state: SyncState
    ) -> ExpenseReconciliation.LocalRecord {
        ExpenseReconciliation.LocalRecord(
            id: id, ownerId: owner, updatedAt: updatedAt, syncState: state
        )
    }

    // MARK: - Cross-device create

    func testRemoteRowWithNoLocalCounterpartIsInserted() {
        let id = UUID()
        let plan = ExpenseReconciliation.plan(
            local: [],
            remote: [row(id: id, owner: ownerA, amount: 42, updatedAt: t0)],
            activeOwnerId: ownerA
        )

        XCTAssertEqual(plan.inserts.map(\.id), [id])
        XCTAssertEqual(plan.inserts.first?.amount, 42)
        XCTAssertTrue(plan.updates.isEmpty)
        XCTAssertTrue(plan.deletions.isEmpty)
    }

    func testNewerRemoteVersionOfASyncedRowIsApplied() {
        let id = UUID()
        let plan = ExpenseReconciliation.plan(
            local: [local(id: id, owner: ownerA, updatedAt: t0, state: .synced)],
            remote: [row(id: id, owner: ownerA, amount: 99, updatedAt: t1)],
            activeOwnerId: ownerA
        )

        XCTAssertEqual(plan.updates.map(\.id), [id])
        XCTAssertEqual(plan.updates.first?.row.amount, 99)
        XCTAssertTrue(plan.inserts.isEmpty)
        XCTAssertTrue(plan.deletions.isEmpty)
    }

    func testOlderOrEqualRemoteVersionIsIgnored() {
        let olderID = UUID()
        let sameID = UUID()
        let plan = ExpenseReconciliation.plan(
            local: [
                local(id: olderID, owner: ownerA, updatedAt: t1, state: .synced),
                local(id: sameID, owner: ownerA, updatedAt: t0, state: .synced),
            ],
            remote: [
                row(id: olderID, owner: ownerA, updatedAt: t0),
                row(id: sameID, owner: ownerA, updatedAt: t0),
            ],
            activeOwnerId: ownerA
        )

        XCTAssertTrue(plan.isEmpty)
    }

    // MARK: - Repeated pulls are no-ops

    func testRepeatedPullWithNoChangesProducesAnEmptyPlan() {
        let id = UUID()
        let remote = [row(id: id, owner: ownerA, updatedAt: t0)]
        let localRows = [local(id: id, owner: ownerA, updatedAt: t0, state: .synced)]

        for _ in 0..<3 {
            let plan = ExpenseReconciliation.plan(
                local: localRows, remote: remote, activeOwnerId: ownerA
            )
            XCTAssertTrue(plan.isEmpty)
        }
    }

    func testDuplicatedRemoteIDsAcrossPagesDoNotProduceDuplicateInserts() {
        let id = UUID()
        let plan = ExpenseReconciliation.plan(
            local: [],
            remote: [
                row(id: id, owner: ownerA, amount: 10, updatedAt: t0),
                row(id: id, owner: ownerA, amount: 20, updatedAt: t1),
            ],
            activeOwnerId: ownerA
        )

        XCTAssertEqual(plan.inserts.count, 1)
        XCTAssertEqual(plan.inserts.first?.amount, 20, "the last page's row wins")
    }

    // MARK: - Cross-device delete (absence)

    func testSyncedRowAbsentFromTheSnapshotIsDeletedLocally() {
        let id = UUID()
        let plan = ExpenseReconciliation.plan(
            local: [local(id: id, owner: ownerA, updatedAt: t0, state: .synced)],
            remote: [],
            activeOwnerId: ownerA
        )

        XCTAssertEqual(plan.deletions, [id])
    }

    func testUnpushedRowsAreNeverDeletedByAbsence() {
        let localOnly = UUID()
        let dirty = UUID()
        let tombstone = UUID()
        let plan = ExpenseReconciliation.plan(
            local: [
                local(id: localOnly, owner: ownerA, updatedAt: t0, state: .local),
                local(id: dirty, owner: ownerA, updatedAt: t0, state: .dirty),
                local(id: tombstone, owner: ownerA, updatedAt: t0, state: .deleted),
            ],
            remote: [],
            activeOwnerId: ownerA
        )

        XCTAssertTrue(
            plan.deletions.isEmpty,
            "rows that were never pushed (or whose delete is in flight) must survive a pull"
        )
    }

    // MARK: - Offline edit vs. remote delete

    func testLocalDirtyEditWinsOverARemoteDeleteThisCycle() {
        let id = UUID()
        let plan = ExpenseReconciliation.plan(
            local: [local(id: id, owner: ownerA, updatedAt: t1, state: .dirty)],
            remote: [],
            activeOwnerId: ownerA
        )

        // Not deleted, not overwritten — the next push re-creates it remotely.
        XCTAssertTrue(plan.isEmpty)
    }

    func testLocalDirtyEditIsNotOverwrittenByANewerRemoteRow() {
        let id = UUID()
        let plan = ExpenseReconciliation.plan(
            local: [local(id: id, owner: ownerA, updatedAt: t0, state: .dirty)],
            remote: [row(id: id, owner: ownerA, amount: 99, updatedAt: t1)],
            activeOwnerId: ownerA
        )

        XCTAssertTrue(
            plan.updates.isEmpty,
            "a pending local edit must not be clobbered mid-cycle; last-writer-wins on push"
        )
    }

    func testLocalTombstoneIsNotResurrectedByTheRemoteRow() {
        let id = UUID()
        let plan = ExpenseReconciliation.plan(
            local: [local(id: id, owner: ownerA, updatedAt: t0, state: .deleted)],
            remote: [row(id: id, owner: ownerA, updatedAt: t1)],
            activeOwnerId: ownerA
        )

        XCTAssertTrue(plan.isEmpty, "a pending delete must not be undone by the pull")
    }

    // MARK: - Owner isolation

    func testRowsFromAnotherAccountAreIgnoredInBothDirections() {
        let theirRemote = UUID()
        let theirLocal = UUID()
        let plan = ExpenseReconciliation.plan(
            local: [local(id: theirLocal, owner: ownerB, updatedAt: t0, state: .synced)],
            remote: [row(id: theirRemote, owner: ownerB, updatedAt: t0)],
            activeOwnerId: ownerA
        )

        XCTAssertTrue(plan.inserts.isEmpty, "another account's row must never be inserted")
        XCTAssertTrue(
            plan.deletions.isEmpty,
            "another account's local row must never be deleted by our snapshot"
        )
    }

    func testAnEmptyActiveOwnerProducesNoPlan() {
        let id = UUID()
        let plan = ExpenseReconciliation.plan(
            local: [local(id: id, owner: ownerA, updatedAt: t0, state: .synced)],
            remote: [],
            activeOwnerId: "   "
        )

        XCTAssertTrue(plan.isEmpty, "a signed-out pull must never delete anything")
    }

    func testLegacyUnownedLocalRowIsReconciledForTheSignedInUser() {
        let id = UUID()
        let plan = ExpenseReconciliation.plan(
            local: [local(id: id, owner: nil, updatedAt: t0, state: .synced)],
            remote: [row(id: id, owner: ownerA, amount: 77, updatedAt: t1)],
            activeOwnerId: ownerA
        )

        XCTAssertEqual(plan.updates.map(\.id), [id])
        XCTAssertEqual(plan.updates.first?.row.amount, 77)
    }

    func testOwnerComparisonIgnoresCase() {
        let id = UUID()
        let plan = ExpenseReconciliation.plan(
            local: [],
            remote: [row(id: id, owner: "OWNER-A", updatedAt: t0)],
            activeOwnerId: "owner-a"
        )

        XCTAssertEqual(plan.inserts.map(\.id), [id])
    }

    // MARK: - Row decoding

    func testRemoteRowDecodesPostgRESTShapes() throws {
        let id = UUID()
        let json = """
        [
          {
            "id": "\(id.uuidString)",
            "user_id": "owner-a",
            "amount": "12.34",
            "category_name": "Food",
            "note": "Lunch",
            "date": "2026-10-01T10:00:00+00:00",
            "created_at": "2026-10-01T10:00:00.123456+00:00",
            "updated_at": "2026-10-01T10:00:00Z"
          },
          {
            "id": "\(UUID().uuidString)",
            "user_id": "owner-a",
            "amount": 5,
            "category_name": "Transport",
            "note": null,
            "date": "2026-10-01T10:00:00+00:00",
            "created_at": "2026-10-01T10:00:00+00:00",
            "updated_at": "2026-10-01T10:00:00+00:00"
          }
        ]
        """.data(using: .utf8)!

        let rows = try JSONDecoder().decode([RemoteExpenseRow].self, from: json)

        XCTAssertEqual(rows.count, 2)
        XCTAssertEqual(rows[0].id, id)
        // Exact decimal, never routed through Double.
        XCTAssertEqual(rows[0].amount, Decimal(string: "12.34"))
        XCTAssertEqual(rows[0].note, "Lunch")
        XCTAssertEqual(rows[1].amount, 5)
        XCTAssertNil(rows[1].note)
    }
}
