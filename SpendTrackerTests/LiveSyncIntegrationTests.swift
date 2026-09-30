import XCTest
import SwiftData
@testable import SpendTracker

#if canImport(Supabase)
import Supabase
#endif

/// LIVE integration tests against the real Supabase project.
///
/// These hit the network and a real project, so they are gated on
/// `SupabaseClientProvider.shared.isConfigured`. When keys are absent they
/// XCTSkip. Each run uses fresh UUIDs and a timestamped email so reruns never
/// collide. Rows created in TEST 1 & 2 are cleaned up in tearDown.
///
/// QA runtime paths covered:
///  - TEST 1 (2a): a real Expense upsert lands a row scoped to the signed-in user.
///  - TEST 2 (2b): a real custom Category upsert lands a row under that user.
///  - TEST 3 (3):  a failed write leaves the local record `.local`, not `.synced`.
final class LiveSyncIntegrationTests: XCTestCase {

    // Track created live rows for cleanup.
    private var createdExpenseIds: [UUID] = []
    private var createdCategoryIds: [UUID] = []
    private var didSignIn = false

    /// Install a TEST-ONLY in-memory auth store and rebuild the shared client
    /// with it BEFORE any test writes. Production uses the default secure
    /// Keychain; this override applies only inside the XCTest host, where
    /// Keychain writes can fail and surface as `sessionMissing`. Combined with
    /// the explicit `setSession(...)` re-seat below, this keeps the live
    /// session available to the repositories without weakening production.
    override class func setUp() {
        super.setUp()
        #if canImport(Supabase)
        if SupabaseClientProvider.shared.isConfigured {
            SupabaseClientProvider.shared.configureClientForTesting(storage: InMemoryAuthStorage())
        }
        #endif
    }

    // MARK: - Decodable row shapes for independent verification

    #if canImport(Supabase)
    private struct ExpenseVerifyRow: Decodable {
        let id: String
        let user_id: String
        let amount: Double        // numeric comes back as a JSON number
        let category_name: String
        let date: String?
        let remote_id: String?
    }

    private struct CategoryVerifyRow: Decodable {
        let id: String
        let user_id: String
        let name: String
        let is_default: Bool
        let sort_order: Int
    }
    #endif

    // MARK: - Failing stub for TEST 3

    private struct FailingExpenseRepo: ExpenseRepositoring {
        struct DeliberateFailure: Error {}
        func upsert(_ expense: Expense) async throws -> String {
            throw DeliberateFailure()
        }
        func delete(id: UUID) async throws {}
    }

    private struct NoOpCategoryRepo: CategoryRepositoring {
        func upsert(_ category: SpendTracker.Category) async throws {}
        func delete(id: UUID) async throws {}
    }

    // MARK: - Gating

    private func requireConfigured() throws {
        try XCTSkipUnless(
            SupabaseClientProvider.shared.isConfigured,
            "Supabase not configured in this build; skipping live integration test."
        )
    }

    /// Signs up a fresh user; if the project is configured with email-confirmation
    /// the signUp may not yield an active session. Returns the resolved userId, or
    /// nil if a confirmation gate blocks obtaining a session.
    /// The `outcome` inout string records what happened for the report.
    #if canImport(Supabase)
    @discardableResult
    private func signInFreshUser(outcome: inout String) async throws -> String? {
        let client = SupabaseClientProvider.shared.client!
        let email = "tabby-qa+\(UUID().uuidString.prefix(8).lowercased())@example.com"
        let password = "QaTest!\(UUID().uuidString.prefix(8))A1"

        // Call signUp directly on the SDK so we can inspect AuthResponse:
        //  - .session(...) => project auto-confirms, we have a live session.
        //  - .user(...)    => email confirmation is pending (no session).
        let response = try await client.auth.signUp(email: email, password: password)
        if let session = response.session {
            didSignIn = true
            outcome = "live_session"
            // The default SupabaseClient session storage can fail to persist in a
            // unit-test host, which later surfaces as `sessionMissing` inside the
            // repositories (they call `client.auth.session`). Re-seat the session
            // explicitly so the session manager holds it for subsequent writes.
            _ = try? await client.auth.setSession(
                accessToken: session.accessToken,
                refreshToken: session.refreshToken
            )
            let seated = client.auth.currentSession?.user.id.uuidString ?? "nil"
            print("LIVE-SYNC signUp returned live session for \(email) userId=\(session.user.id.uuidString) seated=\(seated)")
            return session.user.id.uuidString
        }

        // Only a user came back — try an explicit signIn in case the SDK already
        // persisted a session, otherwise this is a confirmation gate.
        do {
            let signInSession = try await client.auth.signIn(email: email, password: password)
            didSignIn = true
            outcome = "live_session"
            print("LIVE-SYNC signIn established session for \(email) userId=\(signInSession.user.id.uuidString)")
            return signInSession.user.id.uuidString
        } catch {
            print("LIVE-SYNC signIn after signUp failed: \(error.localizedDescription)")
        }

        outcome = "email_confirmation_required"
        print("LIVE-SYNC no active session after signUp/signIn for \(email) — email confirmation gate.")
        return nil
    }
    #endif

    // MARK: - TEST 1 (QA 2a): real expense write + independent verify

    func test1_LiveExpenseUpsertLandsRow() async throws {
        try requireConfigured()
        #if canImport(Supabase)
        let client = SupabaseClientProvider.shared.client!

        var signInOutcome = ""
        let userId = try await signInFreshUser(outcome: &signInOutcome)
        try XCTSkipUnless(
            userId != nil,
            "Email signup did not yield a live session (\(signInOutcome)); cannot exercise authed write."
        )
        let uid = userId!

        let expense = Expense(
            id: UUID(),
            amount: Decimal(string: "12.34")!,
            categoryName: "QA-Test",
            date: Date()
        )
        createdExpenseIds.append(expense.id)

        let repo = SupabaseExpenseRepository()
        let remoteId = try await repo.upsert(expense)
        XCTAssertFalse(remoteId.isEmpty, "upsert should return a non-empty remote id")
        XCTAssertEqual(remoteId, expense.id.uuidString)

        // Independent verification: read the row straight back from Supabase.
        let rows: [ExpenseVerifyRow] = try await client
            .from("expenses")
            .select()
            .eq("id", value: expense.id.uuidString)
            .execute()
            .value

        XCTAssertEqual(rows.count, 1, "expected exactly one expense row for the id")
        let row = try XCTUnwrap(rows.first)
        XCTAssertEqual(row.user_id.lowercased(), uid.lowercased(), "row must be scoped to the signed-in user")
        XCTAssertEqual(row.category_name, "QA-Test")
        XCTAssertEqual(row.amount, 12.34, accuracy: 0.001, "amount must round-trip")

        // Print the returned JSON so it's captured in test output.
        let raw = try await client
            .from("expenses")
            .select()
            .eq("id", value: expense.id.uuidString)
            .execute()
        if let json = String(data: raw.data, encoding: .utf8) {
            print("LIVE-SYNC EXPENSE ROW JSON: \(json)")
        }
        #endif
    }

    // MARK: - TEST 2 (QA 2b): real custom category write + verify

    func test2_LiveCategoryUpsertLandsRow() async throws {
        try requireConfigured()
        #if canImport(Supabase)
        let client = SupabaseClientProvider.shared.client!

        var signInOutcome = ""
        let userId = try await signInFreshUser(outcome: &signInOutcome)
        try XCTSkipUnless(
            userId != nil,
            "Email signup did not yield a live session (\(signInOutcome)); cannot exercise authed write."
        )
        let uid = userId!

        let name = "QA-Custom-\(UUID().uuidString.prefix(8))"
        let category = SpendTracker.Category(
            id: UUID(),
            name: name,
            isDefault: false,
            sortOrder: 0,
            syncState: .local
        )
        createdCategoryIds.append(category.id)

        let repo = SupabaseCategoryRepository()
        try await repo.upsert(category)

        let rows: [CategoryVerifyRow] = try await client
            .from("categories")
            .select()
            .eq("id", value: category.id.uuidString)
            .execute()
            .value

        XCTAssertEqual(rows.count, 1, "expected exactly one category row for the id")
        let row = try XCTUnwrap(rows.first)
        XCTAssertEqual(row.user_id.lowercased(), uid.lowercased(), "category must be scoped to the signed-in user")
        XCTAssertEqual(row.name, name)
        XCTAssertFalse(row.is_default)

        let raw = try await client
            .from("categories")
            .select()
            .eq("id", value: category.id.uuidString)
            .execute()
        if let json = String(data: raw.data, encoding: .utf8) {
            print("LIVE-SYNC CATEGORY ROW JSON: \(json)")
        }
        #endif
    }

    // MARK: - TEST 3 (QA 3): failed write stays unsynced (deterministic, offline)

    @MainActor
    func test3_FailedWriteStaysUnsynced() async throws {
        // No network needed — this is the reliable core of QA path 3.
        let schema = Schema([Expense.self, SpendTracker.Category.self, UserProfile.self])
        let config = ModelConfiguration(schema: schema, isStoredInMemoryOnly: true)
        let container = try ModelContainer(for: schema, configurations: [config])
        let context = ModelContext(container)

        let expense = Expense(amount: Decimal(string: "9.99")!, categoryName: "QA-Fail")
        XCTAssertEqual(expense.syncState, .local, "precondition: starts local")
        context.insert(expense)
        try context.save()

        let engine = SyncEngine(
            modelContext: context,
            expenseRepository: FailingExpenseRepo(),
            categoryRepository: NoOpCategoryRepo()
        )
        await engine.pushUnsyncedExpenses()

        // The deliberately-failing upsert must NOT have marked the record synced.
        let fetched = try context.fetch(FetchDescriptor<Expense>())
        XCTAssertEqual(fetched.count, 1)
        let after = try XCTUnwrap(fetched.first)
        XCTAssertEqual(after.syncState, .local,
                       "A failed write must leave the record .local, never falsely .synced")
        XCTAssertNil(after.remoteId, "no remote id should be set for a failed write")
        print("LIVE-SYNC TEST3 stub: expense.syncState after failed push = \(after.syncState) (raw \(after.syncStateRaw))")
    }

    // MARK: - TEST 3b (optional): REAL repository failure keeps record unsynced

    /// Points the REAL repository at a row that violates a DB constraint
    /// (duplicate custom category name — `unique(user_id, name)`), proving the
    /// live repository throws and, driven through SyncEngine, the record stays
    /// unsynced. This is a bonus over the deterministic stub above.
    @MainActor
    func test3b_RealRepoConstraintViolationStaysUnsynced() async throws {
        try requireConfigured()
        #if canImport(Supabase)
        var signInOutcome = ""
        let userId = try await signInFreshUser(outcome: &signInOutcome)
        try XCTSkipUnless(
            userId != nil,
            "Email signup did not yield a live session (\(signInOutcome)); cannot exercise real-repo failure."
        )

        let dupName = "QA-Dup-\(UUID().uuidString.prefix(8))"

        // First insert succeeds (unique name for this user).
        let first = SpendTracker.Category(id: UUID(), name: dupName, isDefault: false, sortOrder: 0, syncState: .local)
        createdCategoryIds.append(first.id)
        let realRepo = SupabaseCategoryRepository()
        try await realRepo.upsert(first)

        // Second row: different id, SAME name -> violates unique(user_id, name).
        let schema = Schema([Expense.self, SpendTracker.Category.self, UserProfile.self])
        let config = ModelConfiguration(schema: schema, isStoredInMemoryOnly: true)
        let container = try ModelContainer(for: schema, configurations: [config])
        let context = ModelContext(container)

        let dup = SpendTracker.Category(id: UUID(), name: dupName, isDefault: false, sortOrder: 1, syncState: .local)
        createdCategoryIds.append(dup.id)   // in case it somehow lands, clean up
        context.insert(dup)
        try context.save()

        // Confirm the real repo actually throws on the constraint violation.
        var threw = false
        do {
            try await realRepo.upsert(dup)
        } catch {
            threw = true
            print("LIVE-SYNC TEST3b real repo threw as expected: \(error.localizedDescription)")
        }
        XCTAssertTrue(threw, "duplicate (user_id,name) category upsert should throw")

        // Drive it through SyncEngine and assert it stays unsynced.
        let engine = SyncEngine(
            modelContext: context,
            expenseRepository: FailingExpenseRepo(),      // unused for categories path
            categoryRepository: realRepo
        )
        await engine.pushUnsyncedCategories()

        let fetched = try context.fetch(
            FetchDescriptor<SpendTracker.Category>(predicate: #Predicate { $0.name == dupName })
        )
        let after = try XCTUnwrap(fetched.first)
        XCTAssertEqual(after.syncState, .local,
                       "constraint-violating category must remain .local, not falsely .synced")
        print("LIVE-SYNC TEST3b: duplicate category syncState after push = \(after.syncState)")
        #endif
    }

    // MARK: - Cleanup

    override func tearDown() async throws {
        #if canImport(Supabase)
        // Best-effort cleanup; never fail the test on cleanup problems.
        if SupabaseClientProvider.shared.isConfigured {
            let expenseRepo = SupabaseExpenseRepository()
            for id in createdExpenseIds {
                do { try await expenseRepo.delete(id: id) }
                catch { print("LIVE-SYNC cleanup: failed to delete expense \(id): \(error.localizedDescription)") }
            }
            let categoryRepo = SupabaseCategoryRepository()
            for id in createdCategoryIds {
                do { try await categoryRepo.delete(id: id) }
                catch { print("LIVE-SYNC cleanup: failed to delete category \(id): \(error.localizedDescription)") }
            }
            if didSignIn {
                do { try await SupabaseAuthService().signOut() }
                catch { print("LIVE-SYNC cleanup: signOut failed: \(error.localizedDescription)") }
            }
        }
        #endif
        createdExpenseIds.removeAll()
        createdCategoryIds.removeAll()
        try await super.tearDown()
    }
}
