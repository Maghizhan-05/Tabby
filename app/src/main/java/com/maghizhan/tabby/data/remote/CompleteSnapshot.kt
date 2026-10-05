package com.maghizhan.tabby.data.remote

import androidx.annotation.VisibleForTesting

/**
 * A set of remote rows, carrying **who it belongs to** and **whether it is
 * provably complete**.
 *
 * Absence-based deletion in the reconciliation rules is only sound against a
 * complete snapshot: a truncated or shifted result reads as "everything else was
 * deleted elsewhere" and destroys real records. Supabase also caps an unpaged
 * `select` (1000 rows by default), so "I asked for everything" is not proof.
 *
 * Two properties are therefore baked into the type rather than left to callers:
 *
 * 1. **Completeness is proven by an exact server-side count**, not inferred from
 *    a short page. Offset paging across several requests cannot prove it: if a
 *    row earlier in the ordering is deleted between requests, every later row
 *    shifts down one offset and one is skipped entirely — and a skipped row
 *    looks exactly like a remotely deleted row to reconciliation.
 *
 *    When the count cannot be matched the snapshot is still usable, but
 *    [authorizesAbsenceDeletion] is false and reconciliation performs
 *    non-destructive merges only. A concurrent delete therefore degrades to
 *    "merge now, delete next cycle" instead of destroying data.
 *
 * 2. **The snapshot carries the owner it was fetched for.** Without it, a fetch
 *    started as account A and resolving after a switch to account B would be
 *    reconciled against B's local rows: A's rows filter out, B's synced rows
 *    look absent, and they get absence-deleted. [requireOwner] makes the caller
 *    re-assert the active owner before the snapshot can be applied.
 *
 * The constructor is private, and the only production factories are
 * [proved] and [partial] — both of which demand an owner, and only one of which
 * authorises deletion. There is no production path that wraps a bare list.
 */
class CompleteSnapshot<T> private constructor(
    val rows: List<T>,
    /** The authenticated user id this snapshot was fetched for. */
    val ownerId: String,
    /**
     * True only when an exact server-side count matched the rows received.
     * Reconciliation consults this before emitting any deletion.
     */
    val authorizesAbsenceDeletion: Boolean,
    /** Requests used, for diagnostics and for the page-ceiling check. */
    val pagesFetched: Int
) {

    /** Raised when the active owner changed while the snapshot was in flight. */
    class OwnerMismatchException(expected: String, actual: String) :
        Exception("Snapshot belongs to $expected but the active owner is now $actual.")

    /**
     * Returns [rows] only if this snapshot belongs to [activeOwnerId].
     *
     * Call this immediately before applying the snapshot, with the owner read
     * fresh from the live session — that is what closes the account-switch
     * window described above.
     */
    fun requireOwner(activeOwnerId: String): List<T> {
        val active = normalize(activeOwnerId)
        if (active != normalize(ownerId)) throw OwnerMismatchException(ownerId, activeOwnerId)
        return rows
    }

    companion object {
        /** Supabase's own per-request row ceiling. */
        const val MAXIMUM_ROWS = 1000

        /**
         * Hard stop on requests per fetch, so a backend that keeps reporting
         * full pages cannot spin forever.
         */
        const val MAXIMUM_PAGES = 200

        private fun normalize(value: String): String = value.trim().lowercase()

        /**
         * A snapshot whose completeness was PROVEN: the exact count reported by
         * the server matched the rows received. Absence deletion is authorised.
         */
        fun <T> proved(rows: List<T>, ownerId: String, pagesFetched: Int): CompleteSnapshot<T> =
            CompleteSnapshot(rows.toList(), ownerId, true, pagesFetched)

        /**
         * A snapshot that could NOT be proven complete — a missing count, a
         * count that moved mid-fetch, or the page ceiling. Safe to merge from,
         * never safe to delete from.
         */
        fun <T> partial(rows: List<T>, ownerId: String, pagesFetched: Int): CompleteSnapshot<T> =
            CompleteSnapshot(rows.toList(), ownerId, false, pagesFetched)

        /**
         * Test-only constructor, [VisibleForTesting] with `NONE` so any
         * production use is a lint error. Tests need to build a known-complete
         * snapshot without a server.
         */
        @VisibleForTesting(otherwise = VisibleForTesting.NONE)
        fun <T> forTesting(
            rows: List<T>,
            ownerId: String,
            authorizesAbsenceDeletion: Boolean = true
        ): CompleteSnapshot<T> =
            CompleteSnapshot(rows.toList(), ownerId, authorizesAbsenceDeletion, 1)
    }
}

/** Rows plus the exact total the same server statement reported. */
data class CountedRows<T>(val rows: List<T>, val exactTotal: Long?)
