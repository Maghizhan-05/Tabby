package com.maghizhan.tabby.data.remote

import androidx.annotation.VisibleForTesting

/**
 * Remote rows plus two facts about them: **which account** they belong to, and
 * **whether their completeness was proven**.
 *
 * Absence-based deletion is only sound against a result set that is provably
 * complete and provably the right account's. Get either wrong and reconciliation
 * deletes live records.
 *
 * There is no public factory. The only way to obtain a snapshot is [fetch],
 * which applies the completeness rule itself — so a caller cannot wrap an
 * arbitrary list and declare it proven. That matters because "is this complete?"
 * is a property of *how the rows were obtained*, which a value object can never
 * verify after the fact.
 *
 * ## Why an exact count alone does not prove completeness across requests
 *
 * Offset ranges are evaluated against a different server statement per request.
 * Delete one early row and insert one later row between two pages and the total
 * is unchanged while one row is never returned — the count "proves" a snapshot
 * that is missing a record, and reconciliation reads that record as deleted
 * remotely. Duplicate ids across overlapping pages forge the same false proof by
 * padding the count back up.
 *
 * So the rule is deliberately strict: completeness is proven only by a **single
 * request** whose exact server-side count matches the rows received, with no
 * duplicate ids. Anything spanning more than one request is unproven: usable for
 * merging, never for deletion.
 *
 * ## KNOWN LIMITATION: deletions stop converging above [PAGE_SIZE] rows
 *
 * This is a deliberate, accepted trade-off, not an oversight — read it before
 * changing anything here.
 *
 * Once one entity type holds more than [PAGE_SIZE] (1000) rows for a single
 * account, no fetch can ever be proven complete, because every fetch then spans
 * more than one request. From that point on, for that entity type:
 *
 * - additions and updates still sync normally, in both directions;
 * - local deletions still propagate to the backend (they are pushed as explicit
 *   tombstones, which never rely on absence);
 * - **a deletion made on ANOTHER device stops propagating to this one.** The
 *   remote row is gone, but its local copy survives indefinitely, because the
 *   only evidence of a remote deletion is absence from a complete snapshot.
 *
 * The user-visible effect is a record deleted elsewhere that lingers on this
 * device until the account drops back under the threshold. That is strictly
 * better than the alternative: authorizing deletion from an unproven snapshot
 * silently destroys records the user still has, and financial data lost that way
 * is unrecoverable. A lingering row is visible and fixable; a deleted row is not.
 *
 * Closing this properly needs a server-side consistent snapshot (an RPC reading
 * the whole set in one statement, or a documented keyset/watermark strategy that
 * cannot shift). Both are backend changes, which are out of scope for this
 * checkpoint by explicit decision, so the safe degradation stands for now.
 */
class CompleteSnapshot<T> private constructor(
    val rows: List<T>,
    /** The session these rows were fetched under. */
    val session: ActiveSession,
    /** True only when completeness was proven; consulted before any deletion. */
    val authorizesAbsenceDeletion: Boolean,
    val requestsMade: Int,
    /** Why deletion is not authorised, for diagnostics. Null when it is. */
    val unprovenReason: String?
) {

    val ownerId: String get() = session.ownerId

    /** Raised when the active session is not the one the snapshot was fetched under. */
    class SessionMismatchException(expected: ActiveSession, actual: ActiveSession) : Exception(
        "Snapshot belongs to generation ${expected.generation} but the active session " +
            "is generation ${actual.generation}."
    )

    /**
     * Returns [rows] only if [binding] is still the session this snapshot was
     * fetched under.
     *
     * This closes the account-switch window: a fetch started as A and resolving
     * after a switch to B would otherwise be reconciled against B's local rows,
     * where A's rows filter out, B's synced rows look absent, and every one of
     * them is absence-deleted.
     */
    fun requireSession(binding: SessionBinding): List<T> {
        if (binding.session != session) {
            throw SessionMismatchException(session, binding.session)
        }
        return rows
    }

    /** One page of rows plus the exact total the same statement reported. */
    data class Page<T>(val rows: List<T>, val exactTotal: Long?)

    companion object {
        /**
         * Rows per request. Supabase's own default ceiling is 1000, so asking for
         * more in one statement is silently truncated — and a truncated page that
         * looked complete would authorise deleting everything beyond it.
         */
        const val PAGE_SIZE = 1000L

        /** Hard stop so a backend reporting endless full pages cannot spin. */
        const val MAXIMUM_REQUESTS = 200

        /**
         * Fetches rows under [binding] and decides for itself whether the result
         * is provably complete.
         *
         * @param idOf extracts a row's identity, used to detect duplicates.
         * @param fetchPage requests rows `[from, to]` inclusive and returns them
         *   with the exact total the SAME statement reported (`Count.EXACT`).
         *
         * The session is revalidated before the first request, so a cycle cannot
         * even begin against a session that has already changed.
         */
        suspend fun <T> fetch(
            binding: SessionBinding,
            idOf: (T) -> Any,
            pageSize: Long = PAGE_SIZE,
            fetchPage: suspend (from: Long, to: Long) -> Page<T>
        ): CompleteSnapshot<T> {
            binding.revalidate()

            val rows = mutableListOf<T>()
            val seen = mutableSetOf<Any>()
            var duplicateSeen = false
            var firstTotal: Long? = null
            var totalMoved = false
            var requests = 0

            while (requests < MAXIMUM_REQUESTS) {
                val from = requests * pageSize
                val page = fetchPage(from, from + pageSize - 1)
                requests++

                if (firstTotal == null) {
                    firstTotal = page.exactTotal
                } else if (page.exactTotal != null && page.exactTotal != firstTotal) {
                    totalMoved = true
                }

                for (row in page.rows) {
                    if (!seen.add(idOf(row))) duplicateSeen = true
                }
                rows += page.rows

                if (page.rows.size < pageSize) break
                if (firstTotal != null && rows.size >= firstTotal) break
            }

            // The session is revalidated AFTER assembling too, not only before
            // the first request. A fetch can span an account switch, and rows
            // belonging to the previous account must never be handed back as a
            // snapshot the caller may act on.
            binding.revalidate()

            val reason = when {
                firstTotal == null -> "the backend reported no exact row count"
                duplicateSeen -> "the result contained duplicate ids"
                totalMoved -> "the row count changed between requests"
                requests > 1 ->
                    "the result spanned $requests requests, and offset ranges across " +
                        "separate statements cannot prove completeness"
                rows.size.toLong() != firstTotal ->
                    "received ${rows.size} rows but the backend counted $firstTotal"
                else -> null
            }

            return CompleteSnapshot(
                rows = rows.toList(),
                session = binding.session,
                authorizesAbsenceDeletion = reason == null,
                requestsMade = requests,
                unprovenReason = reason
            )
        }

        /**
         * Test-only constructor, [VisibleForTesting] with `NONE` so any
         * production use is a lint error. Tests need to assert reconciliation
         * behaviour for proven and unproven snapshots without a server.
         */
        @VisibleForTesting(otherwise = VisibleForTesting.NONE)
        fun <T> forTesting(
            rows: List<T>,
            session: ActiveSession,
            authorizesAbsenceDeletion: Boolean = true
        ): CompleteSnapshot<T> = CompleteSnapshot(
            rows = rows.toList(),
            session = session,
            authorizesAbsenceDeletion = authorizesAbsenceDeletion,
            requestsMade = 1,
            unprovenReason = if (authorizesAbsenceDeletion) null else "test-constructed as unproven"
        )
    }
}
