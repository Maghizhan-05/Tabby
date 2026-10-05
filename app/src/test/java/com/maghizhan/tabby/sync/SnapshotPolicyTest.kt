package com.maghizhan.tabby.sync

import com.maghizhan.tabby.data.remote.ActiveSession
import com.maghizhan.tabby.data.remote.CompleteSnapshot
import com.maghizhan.tabby.data.remote.SessionBinding
import com.maghizhan.tabby.data.remote.SessionChangedException
import com.maghizhan.tabby.data.remote.SessionProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When a snapshot may authorise deleting local rows.
 *
 * This is the most destructive decision in the sync layer: a wrongly "proven"
 * snapshot silently destroys records the user still has. Every case below is a
 * way a result can LOOK complete without being complete.
 */
class SnapshotPolicyTest {

    private val ownerA = "11111111-1111-1111-1111-111111111111"
    private val ownerB = "22222222-2222-2222-2222-222222222222"

    private class FixedSessions(private val session: ActiveSession?) : SessionProvider {
        override suspend fun current(): ActiveSession? = session
    }

    private suspend fun binding(owner: String = ownerA, generation: Long = 1) =
        SessionBinding.bind(FixedSessions(ActiveSession(owner, generation)))

    private fun row(id: Int) = id

    @Test
    fun `a single page matching the exact count is proven`() = runTest {
        val snapshot = CompleteSnapshot.fetch<Int>(binding(), { it }) { _, _ ->
            CompleteSnapshot.Page(rows = listOf(row(1), row(2)), exactTotal = 2)
        }

        assertTrue(snapshot.authorizesAbsenceDeletion)
        assertNull(snapshot.unprovenReason)
        assertEquals(2, snapshot.rows.size)
    }

    @Test
    fun `an empty page with a zero count is proven`() = runTest {
        val snapshot = CompleteSnapshot.fetch<Int>(binding(), { it }) { _, _ ->
            CompleteSnapshot.Page(rows = emptyList(), exactTotal = 0)
        }

        // A genuinely empty account must still be able to clear local rows,
        // otherwise a remote "delete everything" never propagates.
        assertTrue(snapshot.authorizesAbsenceDeletion)
    }

    @Test
    fun `a missing count is never proven`() = runTest {
        val snapshot = CompleteSnapshot.fetch<Int>(binding(), { it }) { _, _ ->
            CompleteSnapshot.Page(rows = listOf(row(1)), exactTotal = null)
        }

        assertFalse(snapshot.authorizesAbsenceDeletion)
        assertNotNull(snapshot.unprovenReason)
    }

    @Test
    fun `a multi-request result is never proven even when the count matches`() = runTest {
        var call = 0
        val snapshot = CompleteSnapshot.fetch<Int>(binding(), { it }, pageSize = 2) { _, _ ->
            call++
            // Two pages that add up exactly to the reported total. Equal-cardinality
            // churn (an early row deleted, a later one inserted between requests)
            // produces exactly this shape while silently skipping a row, so the
            // arithmetic agreeing proves nothing.
            if (call == 1) CompleteSnapshot.Page(listOf(row(1), row(2)), exactTotal = 4)
            else CompleteSnapshot.Page(listOf(row(3), row(4)), exactTotal = 4)
        }

        assertEquals(4, snapshot.rows.size)
        assertFalse(
            "a result assembled from multiple requests is not a consistent snapshot",
            snapshot.authorizesAbsenceDeletion
        )
        assertNotNull(snapshot.unprovenReason)
    }

    @Test
    fun `duplicate ids cannot pad a page up to the expected count`() = runTest {
        val snapshot = CompleteSnapshot.fetch<Int>(binding(), { it }) { _, _ ->
            // Three rows, two identical: cardinality matches the count, but one
            // distinct row is missing.
            CompleteSnapshot.Page(rows = listOf(row(1), row(1), row(2)), exactTotal = 3)
        }

        assertFalse(
            "duplicates forge the count; distinct rows must be counted",
            snapshot.authorizesAbsenceDeletion
        )
        assertNotNull(snapshot.unprovenReason)
    }

    @Test
    fun `fewer rows than the count is not proven`() = runTest {
        val snapshot = CompleteSnapshot.fetch<Int>(binding(), { it }) { _, _ ->
            CompleteSnapshot.Page(rows = listOf(row(1)), exactTotal = 5)
        }
        assertFalse(snapshot.authorizesAbsenceDeletion)
    }

    @Test
    fun `an account switch during the fetch aborts rather than returning rows`() = runTest {
        val sessions = object : SessionProvider {
            var generation = 1L
            override suspend fun current() = ActiveSession(ownerA, generation)
        }
        val bound = SessionBinding.bind(sessions)

        val failure = runCatching {
            CompleteSnapshot.fetch<Int>(bound, { it }) { _, _ ->
                sessions.generation = 2
                CompleteSnapshot.Page(rows = listOf(row(1)), exactTotal = 1)
            }
        }

        assertTrue(failure.exceptionOrNull() is SessionChangedException)
    }

    @Test
    fun `a snapshot refuses to be applied under a different session`() = runTest {
        val snapshot = CompleteSnapshot.forTesting(listOf(row(1)), ActiveSession(ownerA, 1))
        val other = binding(owner = ownerB, generation = 2)

        // This is the A-fetch-lands-after-switching-to-B case: without the
        // check, reconciliation would filter out A's rows and absence-delete B's.
        val failure = runCatching { snapshot.requireSession(other) }
        assertTrue(failure.exceptionOrNull() is CompleteSnapshot.SessionMismatchException)
    }

    /**
     * Pins the documented >1000-row limitation as actual behaviour.
     *
     * Above [CompleteSnapshot.PAGE_SIZE] rows for one account, no fetch can be
     * proven complete, so remote deletions stop converging on this device while
     * additions and updates keep working. That trade-off is accepted and written
     * up in the README and in CompleteSnapshot's docs — this test exists so the
     * boundary cannot move silently, in either direction: tightening it would
     * break deletion for ordinary accounts, loosening it would authorize
     * deletion from a result set that can skip rows.
     */
    @Test
    fun `an account at the page ceiling still proves completeness`() = runTest {
        val size = CompleteSnapshot.PAGE_SIZE.toInt()
        val snapshot = CompleteSnapshot.fetch<Int>(binding(), { it }) { _, _ ->
            CompleteSnapshot.Page(rows = (1..size).toList(), exactTotal = size.toLong())
        }

        // Exactly at the ceiling this is still ONE request, so it is proven.
        assertEquals(1, snapshot.requestsMade)
        assertTrue(snapshot.authorizesAbsenceDeletion)
    }

    @Test
    fun `one row past the ceiling can no longer authorize deletion`() = runTest {
        val size = CompleteSnapshot.PAGE_SIZE.toInt()
        val total = size + 1L
        var call = 0

        val snapshot = CompleteSnapshot.fetch<Int>(binding(), { it }) { _, _ ->
            call++
            if (call == 1) CompleteSnapshot.Page((1..size).toList(), exactTotal = total)
            else CompleteSnapshot.Page(listOf(size + 1), exactTotal = total)
        }

        // Every row was retrieved and the arithmetic agrees...
        assertEquals(total.toInt(), snapshot.rows.size)
        // ...but it took two requests, so deletion is withheld. Merging is
        // unaffected: the rows are still returned for insert/update.
        assertEquals(2, snapshot.requestsMade)
        assertFalse(
            "a record deleted on another device will linger rather than risk " +
                "destroying one the user still has",
            snapshot.authorizesAbsenceDeletion
        )
        assertNotNull(snapshot.unprovenReason)
    }

    @Test
    fun `a snapshot applies under its own session`() = runTest {
        val bound = binding()
        val snapshot = CompleteSnapshot.forTesting(listOf(row(1)), ActiveSession(ownerA, 1))

        assertEquals(listOf(row(1)), snapshot.requireSession(bound))
    }
}
