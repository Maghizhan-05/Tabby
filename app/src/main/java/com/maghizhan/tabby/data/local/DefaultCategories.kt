package com.maghizhan.tabby.data.local

import android.content.Context
import com.maghizhan.tabby.data.local.entity.CategoryEntity
import com.maghizhan.tabby.data.sync.SyncState
import java.util.UUID

/**
 * Remembers that this device has already seeded its default categories.
 *
 * Needed because row presence cannot answer the question. Seeding used to skip
 * whenever ANY category existed, which conflated three different situations:
 *
 *  - the user deliberately deleted the defaults (must not be re-seeded),
 *  - sync pulled the account's own categories before seeding ran (defaults were
 *    then skipped forever — the two ran concurrently from `onCreate`, so which
 *    landed first was a race, and losing it permanently cost the user their
 *    eight presets),
 *  - a genuinely fresh install (must be seeded).
 *
 * A one-time durable marker distinguishes "already done" from "there happen to
 * be rows", which makes the outcome independent of who wins the race.
 */
interface SeedMarker {
    fun hasSeeded(): Boolean
    fun markSeeded()
    fun clear()
}

/** The production marker. */
class PreferencesSeedMarker(context: Context) : SeedMarker {

    private val preferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override fun hasSeeded(): Boolean = preferences.getBoolean(KEY, false)

    override fun markSeeded() = preferences.edit().putBoolean(KEY, true).apply()
    override fun clear() = preferences.edit().clear().apply()

    private companion object {
        const val FILE = "tabby_seeding"
        const val KEY = "default_categories_seeded"
    }
}

/** In-memory marker, for tests. */
class InMemorySeedMarker(private var seeded: Boolean = false) : SeedMarker {
    override fun hasSeeded(): Boolean = seeded
    override fun markSeeded() { seeded = true }
    override fun clear() { seeded = false }
}

/**
 * Seeds the default categories on first run.
 *
 * The same eight names, in the same order, as the iOS
 * `SharedModelContainer.defaultCategoryNames` — expenses reference categories by
 * NAME, so a different list (or a different spelling) would make a spend logged
 * on one platform show up uncategorised on the other.
 *
 * Seeded rows are `isDefault = true`, `ownerId = null`, `SYNCED`:
 *
 * - no owner, because they exist before any account does and are shared;
 * - SYNCED, so the coordinator never tries to push them — they are presets, not
 *   user data, and uploading them would create eight junk rows per account;
 * - `isDefault`, which is what makes the store refuse to let a signed-in user
 *   claim or delete them.
 */
object DefaultCategories {

    val NAMES = listOf(
        "Food", "Transport", "Groceries", "Bills",
        "Shopping", "Entertainment", "Health", "Other"
    )

    /**
     * Inserts the defaults exactly once per device, recorded by [marker].
     *
     * Keyed on the marker rather than on row emptiness. Emptiness looked right —
     * a user who deleted a default must not have it reappear — but it also meant
     * that a sync run arriving first made seeding skip permanently, which is a
     * race the user loses silently. The marker preserves the deliberate-deletion
     * guarantee (it is set the first time seeding runs, so a later launch never
     * re-seeds) without tying the decision to what sync happens to have pulled.
     *
     * Names already present are skipped, so an account that legitimately has a
     * "Food" category does not end up with two.
     */
    suspend fun seedIfNeeded(
        dao: CategoryDao,
        transactions: TransactionRunner,
        marker: SeedMarker
    ) {
        if (marker.hasSeeded()) return
        transactions.inTransaction {
            val existing = dao.allUnscoped().map { it.name.trim().lowercase() }.toSet()
            val missing = NAMES.filter { it.trim().lowercase() !in existing }
            if (missing.isNotEmpty()) {
                val nextOrder = dao.allUnscoped().maxOfOrNull { it.sortOrder }?.plus(1) ?: 0
                dao.upsert(
                    missing.mapIndexed { index, name ->
                        CategoryEntity(
                            id = UUID.randomUUID(),
                            name = name,
                            isDefault = true,
                            sortOrder = if (existing.isEmpty()) {
                                NAMES.indexOf(name)
                            } else {
                                nextOrder + index
                            },
                            syncStateRaw = SyncState.SYNCED.raw,
                            remoteId = null,
                            ownerId = null
                        )
                    }
                )
            }
        }
        // Marked only after the transaction committed: a failed seed must be
        // retried on the next launch rather than recorded as done.
        marker.markSeeded()
    }
}
