package com.maghizhan.tabby.data.remote

/**
 * A *provably complete* set of remote rows.
 *
 * Absence-based deletion in the reconciliation rules is only sound against a
 * complete snapshot: a truncated page would read as "everything else was
 * deleted elsewhere" and destroy real records. Supabase also caps an unpaged
 * `select` (1000 rows by default), so "I asked for everything" is not proof.
 *
 * The constructor is private, so the only way to obtain one is [fetchPaging],
 * which owns the termination rule. Reconciliation accepts this type rather than
 * a plain list, which makes passing a partial result a compile error instead of
 * a silent data-loss bug.
 */
class CompleteSnapshot<T> private constructor(val rows: List<T>) {

    class IncompleteSnapshotException(pagesFetched: Int) :
        Exception("Remote snapshot did not terminate after $pagesFetched pages.")

    companion object {
        /** Hard ceiling so a backend with broken range handling fails loudly. */
        const val MAXIMUM_PAGES = 200

        /** Default page size; Supabase's own cap is 1000. */
        const val DEFAULT_PAGE_SIZE = 500

        /**
         * Keeps requesting ordered ranges until a page comes back *shorter* than
         * [pageSize] — the only proof the end was reached.
         *
         * Any error from [fetchPage] propagates: the caller must abort rather
         * than act on a partial result, because an error is not an empty account.
         */
        suspend fun <T> fetchPaging(
            pageSize: Int = DEFAULT_PAGE_SIZE,
            fetchPage: suspend (from: Long, to: Long) -> List<T>
        ): CompleteSnapshot<T> {
            require(pageSize > 0) { "pageSize must be > 0" }

            val all = mutableListOf<T>()
            var offset = 0L

            repeat(MAXIMUM_PAGES) {
                val page = fetchPage(offset, offset + pageSize - 1)
                all += page
                // A short (or empty) page proves completeness.
                if (page.size < pageSize) return CompleteSnapshot(all.toList())
                offset += pageSize
            }

            throw IncompleteSnapshotException(MAXIMUM_PAGES)
        }

        /** Test-only escape hatch for building a snapshot from a known-complete list. */
        fun <T> ofVerified(rows: List<T>): CompleteSnapshot<T> = CompleteSnapshot(rows.toList())
    }
}
