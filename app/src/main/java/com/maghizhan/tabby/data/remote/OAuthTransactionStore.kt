package com.maghizhan.tabby.data.remote

import android.content.Context

/**
 * Remembers that THIS app started an OAuth sign-in, so a callback can be
 * matched to a real transaction.
 *
 * Why it exists: the callback intent filter is exported, so any app on the
 * device can send us a structurally valid `com.maghizhan.tabby://auth-callback`
 * URL. Handling every such intent let an unrelated app force the router to
 * Signed Out simply by delivering a callback with a bad code, because a failed
 * exchange committed `SignedOut(error)` unconditionally. A callback that does
 * not correspond to a sign-in this app began is not ours to act on.
 *
 * Persisted rather than held in memory: the system is free to kill the app while
 * the user is in the browser, so a first-ever OAuth login legitimately arrives
 * in a fresh process. An in-memory flag would be false there and would reject
 * the one callback that matters.
 */
interface OAuthTransactionStore {
    /** True when a sign-in this app started is still awaiting its callback. */
    fun isPending(): Boolean

    /** Called when the consent page is opened. */
    fun begin()

    /** Called once a callback has been handled, successfully or not. */
    fun clear()
}

/** The production store. */
class PreferencesOAuthTransactionStore(context: Context) : OAuthTransactionStore {

    private val preferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override fun isPending(): Boolean = preferences.getBoolean(KEY, false)

    override fun begin() = preferences.edit().putBoolean(KEY, true).apply()

    override fun clear() = preferences.edit().remove(KEY).apply()

    fun clearAll() = preferences.edit().clear().apply()

    private companion object {
        const val FILE = "tabby_oauth_transaction"
        const val KEY = "pending"
    }
}

/** In-memory store, for tests. */
class InMemoryOAuthTransactionStore(private var pending: Boolean = false) : OAuthTransactionStore {
    override fun isPending(): Boolean = pending
    override fun begin() { pending = true }
    override fun clear() { pending = false }
}
