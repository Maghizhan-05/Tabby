package com.maghizhan.tabby.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.appwidget.updateAll
import com.maghizhan.tabby.data.local.ExpenseDao
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

/**
 * Where the widget's sanitised snapshot lives.
 *
 * An interface so the refresh rules are testable without a widget host: the
 * production implementation is backed by SharedPreferences, which survives the
 * app process being killed — the widget must still render after the system has
 * reclaimed the app, and a snapshot held in memory would render blank.
 */
interface WidgetSnapshotStore {
    fun read(): WidgetSnapshot?
    fun write(snapshot: WidgetSnapshot)

    /**
     * Drops the snapshot entirely.
     *
     * Called on sign-out and on an account change. Overwriting with zeros would
     * not be enough: the stale `ownerId` would stay on disk and any later reader
     * could not tell "signed out" from "this account has spent nothing".
     */
    fun clear()
}

/** The production store. */
class PreferencesWidgetSnapshotStore(context: Context) : WidgetSnapshotStore {

    private val preferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private val json = Json { ignoreUnknownKeys = true }

    override fun read(): WidgetSnapshot? {
        val raw = preferences.getString(KEY, null) ?: return null
        // A snapshot written by an older build may no longer decode; treated as
        // absent rather than crashing the widget host process.
        return runCatching { json.decodeFromString<WidgetSnapshot>(raw) }.getOrNull()
    }

    override fun write(snapshot: WidgetSnapshot) {
        preferences.edit().putString(KEY, json.encodeToString(snapshot)).apply()
    }

    override fun clear() = preferences.edit().remove(KEY).apply()

    private companion object {
        const val FILE = "tabby_widget_snapshot"
        const val KEY = "snapshot"
    }
}

/** In-memory store for tests. */
class InMemoryWidgetSnapshotStore : WidgetSnapshotStore {
    private var current: WidgetSnapshot? = null
    override fun read(): WidgetSnapshot? = current
    override fun write(snapshot: WidgetSnapshot) { current = snapshot }
    override fun clear() { current = null }
}

/**
 * Keeps the widget's snapshot current, and tells the widget host to redraw.
 *
 * This is the piece that was missing: `updatePeriodMillis` is 0 because the app
 * is supposed to drive refreshes, but nothing in production ever asked for one,
 * so the widget showed whatever it happened to render first. Refresh is
 * triggered from exactly four places, all of which are moments the displayed
 * numbers can have changed:
 *
 *  - a committed local write (save / delete),
 *  - a completed sync run (reconciliation may have pulled other-device edits),
 *  - a sign-in or account change — [setActiveOwner],
 *  - a sign-out — [setActiveOwner] with null, which CLEARS the snapshot.
 *
 * The active owner is held here rather than looked up per refresh because the
 * session lives behind a suspending, possibly-refreshing token call: blocking a
 * widget refresh on it is how the widget ends up blank.
 */
class WidgetUpdater(
    private val context: Context,
    private val expenseDao: ExpenseDao,
    private val store: WidgetSnapshotStore,
    private val notifyHost: suspend (Context) -> Unit = { TabbyWidget().updateAll(it) }
) {
    private val mutex = Mutex()

    @Volatile
    private var activeOwnerId: String? = null

    /**
     * Records which account the widget may show, and refreshes.
     *
     * Passing null (sign-out) clears the snapshot; passing a different id
     * discards the previous account's snapshot before writing the new one, so an
     * account switch can never leave the old totals on the home screen even for
     * one frame.
     */
    suspend fun setActiveOwner(ownerId: String?) {
        activeOwnerId = ownerId?.trim()?.takeIf { it.isNotEmpty() }
        refresh()
    }

    /** Clears account content and per-instance Glance preferences after deletion. */
    suspend fun clearAllForAccountDeletion() {
        activeOwnerId = null
        store.clear()
        val widget = TabbyWidget()
        GlanceAppWidgetManager(context).getGlanceIds(widget.javaClass).forEach { id ->
            updateAppWidgetState(context, id) { preferences -> preferences.clear() }
        }
        try {
            widget.updateAll(context)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            // The state is already clear; the host can recover on its next update.
        }
    }

    /** Recomputes the snapshot for the active owner and redraws the widget. */
    suspend fun refresh() {
        mutex.withLock {
            val owner = activeOwnerId
            if (owner == null) {
                store.clear()
            } else {
                store.write(
                    WidgetSnapshotFactory.build(
                        ownerId = owner,
                        // Owner-scoped read: the widget never sees a query that
                        // could return another account's rows.
                        expenses = expenseDao.allForOwner(owner)
                    )
                )
            }
        }
        // Outside the lock: the host callback composes the widget, which reads
        // the snapshot back.
        try {
            notifyHost(context)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            // No widget placed, or the host refused the update. The snapshot is
            // already written, so the next render is correct either way; a
            // failed redraw must not fail the sign-in or the user's save.
        }
    }
}
