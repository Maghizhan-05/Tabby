package com.maghizhan.tabby.data.sync

import com.maghizhan.tabby.data.remote.ActiveSession
import com.maghizhan.tabby.data.remote.SessionProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What one scheduled run did, per entity type. */
data class SyncRun(
    val categories: SyncOutcome? = null,
    val friends: SyncOutcome? = null,
    val expenses: SyncOutcome? = null,
    /** Set when the run was skipped entirely. */
    val skippedReason: String? = null
)

/**
 * Decides *when* sync runs, and serializes runs per account.
 *
 * Why this exists separately from the coordinators: a coordinator knows how to
 * perform one cycle correctly, but nothing was calling it. Triggers, ordering
 * and mutual exclusion are a distinct concern, and leaving them implicit is how
 * a correct cycle ends up never running.
 *
 * ## Ordering
 *
 * Categories, then friends, then expenses. An expense references its category by
 * name, so pulling categories first means an expense arriving in the same run
 * already has its category present; the reverse order shows an expense whose
 * category is briefly missing from the picker.
 *
 * ## Serialization
 *
 * One run at a time, per account. Two concurrent runs would each read pending
 * rows, both upload them, and then race on acknowledgement — and a sign-in that
 * triggers a run while a background run is still finishing is the normal case,
 * not an edge case. The lock is held for the whole run.
 *
 * Failures do not abort the run: each entity type is independent, so a failing
 * expense pull should not prevent categories from syncing. Per-type results are
 * reported individually and a null means that type failed this run.
 */
class SyncScheduler(
    private val expenses: ExpenseSyncCoordinator,
    private val categories: CategorySyncCoordinator,
    private val friends: FriendSyncCoordinator,
    private val sessions: SessionProvider,
    /**
     * Called after a completed run with the account that ran.
     *
     * The widget renders a snapshot the app writes, and reconciliation is where
     * another device's edits and deletes land — so a run that changed the local
     * store without refreshing the widget leaves stale totals on the home screen
     * until something else happens to refresh it.
     */
    private val onRunCompleted: suspend (ownerId: String) -> Unit = {}
) {
    private val runMutex = Mutex()

    @Volatile
    private var lastRunSession: ActiveSession? = null

    /**
     * Runs a full cycle for every entity type.
     *
     * Skipped when signed out: there is no account to sync, and treating that as
     * a failure would spam the caller with errors on every app start before
     * sign-in.
     */
    suspend fun runNow(): SyncRun = runMutex.withLock {
        val session = sessions.current()
            ?: return@withLock SyncRun(skippedReason = "signed out")

        lastRunSession = session

        // Dependency order; each is independent on failure.
        val categoryOutcome = categories.synchronizeQuietly()
        val friendOutcome = friends.synchronizeQuietly()
        val expenseOutcome = expenses.synchronizeQuietly()

        // After the local store reflects the run, and inside the lock so two
        // runs cannot interleave a refresh with each other's applies. Failures
        // are swallowed by the callback itself: a widget redraw must not fail a
        // sync run.
        onRunCompleted(session.ownerId)

        SyncRun(
            categories = categoryOutcome,
            friends = friendOutcome,
            expenses = expenseOutcome
        )
    }

    /**
     * Trigger: a session just became available (sign-in, OAuth callback, or a
     * restored session on cold start).
     *
     * This is the trigger that matters most — it is the first moment a cycle
     * *can* run, and before it the local store may hold rows the backend has
     * never seen.
     */
    suspend fun onAuthenticated(): SyncRun = runNow()

    /**
     * Trigger: the access token was refreshed.
     *
     * Deliberately a full run rather than a resume: a refresh bumps the session
     * generation, so any cycle in flight across it aborts. Re-running is the
     * recovery, and a cycle is idempotent — reconciliation is a function of the
     * current local and remote state, not of what the aborted run had done.
     */
    suspend fun onSessionRefreshed(): SyncRun = runNow()

    /**
     * Trigger: the user explicitly asked to refresh, or the app returned to the
     * foreground.
     */
    suspend fun onForegrounded(): SyncRun = runNow()

    /**
     * Trigger: a local write completed, so there is something to push.
     *
     * Reports failure as a result rather than throwing: the user's write already
     * succeeded locally, and a failed push is not something they need to act on
     * — the row stays pending and goes up on the next run.
     */
    suspend fun onLocalWrite(): SyncRun = try {
        runNow()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Throwable) {
        SyncRun(skippedReason = error.message ?: "sync failed")
    }
}
