package com.maghizhan.tabby.sync

import com.maghizhan.tabby.data.remote.CompleteSnapshot
import com.maghizhan.tabby.data.sync.SyncOrchestration
import com.maghizhan.tabby.data.sync.SyncOrchestration.Step
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test

/**
 * Pins the ordering half of the sync contract.
 *
 * The iOS resurrection bug was an ordering bug: pushing before reconciling a
 * complete pull re-uploads rows another device deleted, and the next snapshot
 * then "confirms" them. These tests assert the sequence itself, so the ordering
 * cannot be quietly swapped back.
 */
class SyncOrchestrationTest {

    @Test
    fun `fetch and reconcile both happen before any push`() = runTest {
        val observed = mutableListOf<String>()

        val result = SyncOrchestration.synchronize(
            fetch = {
                observed += "fetch"
                CompleteSnapshot.ofVerified(listOf(1, 2, 3))
            },
            reconcile = { snapshot ->
                observed += "reconcile(${snapshot.rows.size})"
                "plan"
            },
            push = { observed += "push" }
        )

        assertEquals(listOf("fetch", "reconcile(3)", "push"), observed)
        assertEquals(listOf(Step.FETCH, Step.RECONCILE, Step.PUSH), result.steps)
    }

    @Test
    fun `a failed fetch aborts before reconcile or push`() = runTest {
        var reconciled = false
        var pushed = false

        try {
            SyncOrchestration.synchronize<Int, String>(
                fetch = { throw IllegalStateException("offline") },
                reconcile = { reconciled = true; "plan" },
                push = { pushed = true }
            )
            fail("a failed fetch must abort the cycle")
        } catch (_: IllegalStateException) {
        }

        assertFalse("reconcile ran against no snapshot", reconciled)
        assertFalse("push ran without reconciliation - this resurrects deleted rows", pushed)
    }

    @Test
    fun `a failed reconcile aborts before push`() = runTest {
        var pushed = false

        try {
            SyncOrchestration.synchronize<Int, String>(
                fetch = { CompleteSnapshot.ofVerified(listOf(1)) },
                reconcile = { throw IllegalStateException("merge conflict") },
                push = { pushed = true }
            )
            fail("a failed reconcile must abort the cycle")
        } catch (_: IllegalStateException) {
        }

        assertFalse(pushed)
    }
}
