package com.maghizhan.tabby

import android.content.Context
import com.maghizhan.tabby.account.AccountDeletionAnalytics
import com.maghizhan.tabby.account.AccountDeletionCoordinator
import com.maghizhan.tabby.account.LocalAccountData
import com.maghizhan.tabby.account.AccountDeletionRequestStore
import com.maghizhan.tabby.analytics.AnalyticsBuffer
import com.maghizhan.tabby.analytics.AnalyticsEvent
import com.maghizhan.tabby.analytics.AnalyticsTracker
import com.maghizhan.tabby.analytics.AnalyticsUploader
import com.maghizhan.tabby.analytics.PreferencesAnalyticsBuffer
import com.maghizhan.tabby.analytics.PreferencesAnalyticsConsentStore
import com.maghizhan.tabby.analytics.SupabaseAnalyticsTransport
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

    private val appContext = context.applicationContext
    private val database = TabbyDatabase.get(context)
    private val transactions = RoomTransactionRunner(database)

    val sessions: SessionProvider = SupabaseSessionProvider()
    val authService = SupabaseAuthService()
    val deletionRequests = AccountDeletionRequestStore(appContext)

    private val analyticsBuffer: AnalyticsBuffer = PreferencesAnalyticsBuffer(appContext)
    val analyticsConsent = PreferencesAnalyticsConsentStore(appContext)
    val analyticsTracker = AnalyticsTracker(
        consent = analyticsConsent,
        buffer = analyticsBuffer,
        userId = { com.maghizhan.tabby.data.remote.SupabaseClientProvider.currentUserId() }
    )
    private val analyticsUploader = AnalyticsUploader(analyticsBuffer, SupabaseAnalyticsTransport()::upload)

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

    val accountDeletion = AccountDeletionCoordinator(
        analytics = object : AccountDeletionAnalytics {
            override suspend fun track(event: AnalyticsEvent) = analyticsTracker.track(event)
            override suspend fun flush() = analyticsUploader.flushQuietly()
            override suspend fun clear() = analyticsTracker.clear()
        },
        remote = authService::deleteCurrentAccount,
        local = object : LocalAccountData {
            override suspend fun clearRoom() = TabbyDatabase.clearAccountData(appContext)
            override suspend fun clearPreferences() {
                seedMarker.clear()
                oauthTransactions.clear()
                deletionRequests.clear()
            }
            override suspend fun clearWidgets() = widgetUpdater.clearAllForAccountDeletion()
        },
        signOutLocal = authService::signOut
    )

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
        onAnalytics = { run, durationMs ->
            if (run.categories != null && run.friends != null && run.expenses != null) {
                val outcomes = listOf(run.categories, run.friends, run.expenses).filterNotNull()
                analyticsTracker.track(
                    AnalyticsEvent.SyncCompleted(
                        durationMs = durationMs,
                        pushed = outcomes.sumOf { it.pushed },
                        pulled = outcomes.sumOf { it.pulled }
                    )
                )
            }
        },
        // Reconciliation is where another device's edits and deletes land, so a
        // completed run is a moment the widget's numbers can have changed.
        onRunCompleted = { owner ->
            widgetUpdater.setActiveOwner(owner)
            analyticsUploader.flushQuietly()
        }
    )

    companion object {
        @Volatile
        private var instance: AppGraph? = null

        /**
         * Process-wide graph.
         *
         * Widget action callbacks and the configuration activity run outside
         * MainActivity, so they cannot reach its lazily-held graph; without a
         * shared accessor each surface would build its own copy of every
         * coordinator.
         */
        fun from(context: Context): AppGraph =
            instance ?: synchronized(this) {
                instance ?: AppGraph(context.applicationContext).also { instance = it }
            }
    }
}
