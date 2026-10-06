package com.maghizhan.tabby

import android.content.Context
import com.maghizhan.tabby.data.local.CategoryStore
import com.maghizhan.tabby.data.local.DefaultCategories
import com.maghizhan.tabby.data.local.EntryWriter
import com.maghizhan.tabby.data.local.ExpenseStore
import com.maghizhan.tabby.data.local.FriendStore
import com.maghizhan.tabby.data.local.PreferencesSeedMarker
import com.maghizhan.tabby.data.local.RoomTransactionRunner
import com.maghizhan.tabby.data.local.SeedMarker
import com.maghizhan.tabby.data.local.TabbyDatabase
import com.maghizhan.tabby.data.remote.OAuthTransactionStore
import com.maghizhan.tabby.data.remote.PreferencesOAuthTransactionStore
import com.maghizhan.tabby.data.remote.SessionProvider
import com.maghizhan.tabby.data.remote.SupabaseAuthService
import com.maghizhan.tabby.data.remote.SupabaseCategoryRepository
import com.maghizhan.tabby.data.remote.SupabaseExpenseRepository
import com.maghizhan.tabby.data.remote.SupabaseFriendRepository
import com.maghizhan.tabby.data.remote.SupabaseSessionProvider
import com.maghizhan.tabby.data.sync.CategorySyncCoordinator
import com.maghizhan.tabby.data.sync.ExpenseSyncCoordinator
import com.maghizhan.tabby.data.sync.FriendSyncCoordinator
import com.maghizhan.tabby.data.sync.SyncScheduler
import com.maghizhan.tabby.widget.PreferencesWidgetSnapshotStore
import com.maghizhan.tabby.widget.WidgetUpdater

/**
 * The application dependency graph.
 *
 * Hand-written rather than a DI framework: the object graph is small and fixed,
 * and an explicit wiring file makes it obvious at review time what is actually
 * constructed and connected — which is the gap that let the first coordinator
 * exist with no production call site at all.
 */
class AppGraph(context: Context) {

    private val database = TabbyDatabase.get(context)
    private val transactions = RoomTransactionRunner(database)

    val sessions: SessionProvider = SupabaseSessionProvider()
    val authService = SupabaseAuthService()

    /** Durable record of a pending OAuth transaction; see [OAuthTransactionStore]. */
    val oauthTransactions: OAuthTransactionStore = PreferencesOAuthTransactionStore(context)

    /** Read APIs for the UI. Writes always go through the stores below. */
    val expenseDao = database.expenseDao()
    val categoryDao = database.categoryDao()
    val friendDao = database.friendDao()

    /** Local write APIs. Application code uses these, never a raw DAO upsert. */
    val expenseStore = ExpenseStore(database.expenseDao(), transactions)
    val categoryStore = CategoryStore(database.categoryDao(), transactions)
    val friendStore = FriendStore(database.friendDao(), transactions)

    /** One transaction for "create this category AND save this expense". */
    val entryWriter = EntryWriter(expenseStore, categoryStore, transactions)

    /**
     * Keeps the home-screen widget's owner-scoped snapshot current.
     *
     * Wired here and triggered from the scheduler and the activity, because a
     * widget the app never refreshes shows whatever it rendered first — which
     * is what `updatePeriodMillis="0"` quietly depended on and nothing provided.
     */
    val widgetUpdater = WidgetUpdater(
        context = context.applicationContext,
        expenseDao = database.expenseDao(),
        store = PreferencesWidgetSnapshotStore(context)
    )

    private val seedMarker: SeedMarker = PreferencesSeedMarker(context)

    /**
     * Seeds the eight default categories on first run.
     *
     * Suspending and called from the activity rather than done in this
     * constructor: the graph is built on the main thread, and a blocking
     * database write there would be an ANR waiting to happen on a slow device.
     */
    suspend fun seedDefaultCategories() =
        DefaultCategories.seedIfNeeded(database.categoryDao(), transactions, seedMarker)

    private val expenseCoordinator = ExpenseSyncCoordinator(
        dao = database.expenseDao(),
        repository = SupabaseExpenseRepository(),
        transactions = transactions,
        sessions = sessions
    )

    private val categoryCoordinator = CategorySyncCoordinator(
        dao = database.categoryDao(),
        repository = SupabaseCategoryRepository(),
        transactions = transactions,
        sessions = sessions
    )

    private val friendCoordinator = FriendSyncCoordinator(
        dao = database.friendDao(),
        repository = SupabaseFriendRepository(),
        transactions = transactions,
        sessions = sessions
    )

    /** Owns when sync runs; see [SyncScheduler] for the triggers and ordering. */
    val syncScheduler = SyncScheduler(
        expenses = expenseCoordinator,
        categories = categoryCoordinator,
        friends = friendCoordinator,
        sessions = sessions,
        // Reconciliation is where another device's edits and deletes land, so a
        // completed run is a moment the widget's numbers can have changed.
        onRunCompleted = { owner -> widgetUpdater.setActiveOwner(owner) }
    )
}
