package com.maghizhan.tabby.sync

import com.maghizhan.tabby.data.remote.CompleteSnapshot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Pagination must prove completeness, because absence-based deletion acts on it:
 * a snapshot that silently stops early reads as "everything else was deleted
 * elsewhere" and destroys real records.
 */
class CompleteSnapshotTest {

    @Test
    fun `pages until a short page proves the end`() = runTest {
        val backend = (1..250).toList()
        val requested = mutableListOf<Pair<Long, Long>>()

        val snapshot = CompleteSnapshot.fetchPaging(pageSize = 100) { from, to ->
            requested += from to to
            backend.drop(from.toInt()).take((to - from + 1).toInt())
        }

        assertEquals(backend, snapshot.rows)
        assertEquals(listOf(0L to 99L, 100L to 199L, 200L to 299L), requested)
    }

    @Test
    fun `a full final page still forces one more request`() = runTest {
        // Exactly 200 rows with a page size of 100: the second page is full, so
        // it is NOT proof of the end. Stopping there would be indistinguishable
        // from a truncated result, so a third (empty) request must happen.
        val backend = (1..200).toList()
        var requests = 0

        val snapshot = CompleteSnapshot.fetchPaging(pageSize = 100) { from, to ->
            requests++
            backend.drop(from.toInt()).take((to - from + 1).toInt())
        }

        assertEquals(200, snapshot.rows.size)
        assertEquals("exact page boundary must be confirmed by an empty page", 3, requests)
    }

    @Test
    fun `an empty account yields an empty complete snapshot`() = runTest {
        val snapshot = CompleteSnapshot.fetchPaging<Int>(pageSize = 50) { _, _ -> emptyList() }
        assertTrue(snapshot.rows.isEmpty())
    }

    @Test
    fun `a page error aborts instead of returning a partial snapshot`() = runTest {
        // The dangerous failure mode: page 1 succeeds, page 2 fails. Returning
        // what arrived would present 100 rows as the user's entire account.
        try {
            CompleteSnapshot.fetchPaging(pageSize = 100) { from, _ ->
                if (from == 0L) (1..100).toList() else throw IllegalStateException("network died")
            }
            fail("a failed page must propagate, not yield a partial snapshot")
        } catch (e: IllegalStateException) {
            assertEquals("network died", e.message)
        }
    }

    @Test
    fun `a backend that never terminates hits the page ceiling and throws`() = runTest {
        // Guards against a backend ignoring range headers: without a ceiling this
        // would loop forever.
        try {
            CompleteSnapshot.fetchPaging(pageSize = 10) { _, _ -> (1..10).toList() }
            fail("expected IncompleteSnapshotException")
        } catch (e: CompleteSnapshot.IncompleteSnapshotException) {
            assertTrue(e.message!!.contains("${CompleteSnapshot.MAXIMUM_PAGES}"))
        }
    }
}
