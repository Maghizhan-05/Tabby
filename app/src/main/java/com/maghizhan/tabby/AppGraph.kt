package com.maghizhan.tabby

import android.content.Context
import com.maghizhan.tabby.data.local.CategoryStore
import com.maghizhan.tabby.data.local.ExpenseStore
import com.maghizhan.tabby.data.local.FriendStore
import com.maghizhan.tabby.data.local.RoomTransactionRunner
import com.maghizhan.tabby.data.local.TabbyDatabase
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

    /** Local write APIs. Application code uses these, never a raw DAO upsert. */
    val expenseStore = ExpenseStore(database.expenseDao(), transactions)
    val categoryStore = CategoryStore(database.categoryDao(), transactions)
    val friendStore = FriendStore(database.friendDao(), transactions)

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
        sessions = sessions
    )
}
