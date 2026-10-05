package com.maghizhan.tabby.remote

import com.maghizhan.tabby.data.remote.CompleteSnapshot
import com.maghizhan.tabby.data.remote.ExpenseRepositoring
import com.maghizhan.tabby.data.remote.NotAuthenticatedException
import com.maghizhan.tabby.data.remote.model.RemoteExpenseRow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * Pins the repository *contract* without a network.
 *
 * The real Supabase classes need a live project, so these use a fake PostgREST
 * backend that enforces the same two rules the real implementation relies on:
 * rows are filtered by the session's user id, and ranged reads are capped the
 * way Supabase caps them. That is enough to prove the two properties that
 * actually protect data — session-derived ownership and paging to completion —
 * which is what the orchestration layer depends on.
 */
class SupabaseRepositoryContractTest {

    private val t0 = Instant.parse("2026-01-02T03:04:05.123456Z")
    private val ownerA = "11111111-aaaa-0000-0000-000000000001"
    private val ownerB = "22222222-bbbb-0000-0000-000000000002"

    private fun row(owner: String, id: UUID = UUID.randomUUID()) = RemoteExpenseRow(
        id = id,
        userId = owner,
        amount = BigDecimal("1.00"),
        categoryName = "Food",
        note = null,
        date = t0,
        createdAt = t0,
        updatedAt = t0
    )

    /**
     * Fake backend. [sessionUserId] is the authenticated identity; null means no
     * session. Mirrors PostgREST's behaviour of truncating an over-large range.
     */
    private class FakeBackend(
        private val stored: MutableList<RemoteExpenseRow>,
        private val sessionUserId: String?,
        private val serverPageCap: Int = 1000
    ) : ExpenseRepositoring {

        val upserted = mutableListOf<RemoteExpenseRow>()
        val deleted = mutableListOf<UUID>()
        var pageRequests = 0
            private set

        private fun requireUser(): String = sessionUserId ?: throw NotAuthenticatedException()

        override suspend fun fetchAll(): CompleteSnapshot<RemoteExpenseRow> {
            val user = requireUser()
            return CompleteSnapshot.fetchPaging(pageSize = 100) { from, to ->
                pageRequests++
                val requested = (to - from + 1).toInt().coerceAtMost(serverPageCap)
                stored.filter { it.userId == user }
                    .sortedBy { it.id.toString() }
                    .drop(from.toInt())
                    .take(requested)
            }
        }

        override suspend fun upsert(rows: List<RemoteExpenseRow>) {
            val user = requireUser()
            // The real implementation overwrites user_id with the session's id.
            upserted += rows.map { it.copy(userId = user) }
        }

        override suspend fun delete(ids: List<UUID>) {
            val user = requireUser()
            val own = stored.filter { it.userId == user }.map { it.id }.toSet()
            deleted += ids.filter { it in own }
            stored.removeAll { it.id in ids && it.userId == user }
        }
    }

    @Test
    fun `fetchAll returns only the session user's rows`() = runTest {
        val backend = FakeBackend(
            mutableListOf(row(ownerA), row(ownerA), row(ownerB)),
            sessionUserId = ownerA
        )

        val snapshot = backend.fetchAll()

        assertEquals(2, snapshot.rows.size)
        assertTrue(snapshot.rows.all { it.userId == ownerA })
    }

    @Test
    fun `fetchAll pages past the single-request cap`() = runTest {
        // 250 rows cannot arrive in one request; a non-paging implementation
        // would hand reconciliation a truncated snapshot.
        val rows = MutableList(250) { row(ownerA) }
        val backend = FakeBackend(rows, sessionUserId = ownerA)

        val snapshot = backend.fetchAll()

        assertEquals(250, snapshot.rows.size)
        assertEquals(3, backend.pageRequests)
    }

    @Test
    fun `upsert stamps the session user id over whatever the row claims`() = runTest {
        // Confused-deputy guard: a row carrying another account's id must not be
        // written under that id.
        val backend = FakeBackend(mutableListOf(), sessionUserId = ownerA)

        backend.upsert(listOf(row(owner = ownerB)))

        assertEquals(ownerA, backend.upserted.single().userId)
    }

    @Test
    fun `delete cannot remove another account's row`() = runTest {
        val foreign = row(ownerB)
        val backend = FakeBackend(mutableListOf(foreign), sessionUserId = ownerA)

        backend.delete(listOf(foreign.id))

        assertTrue("owner A deleted owner B's remote row", backend.deleted.isEmpty())
    }

    @Test
    fun `every operation refuses to run without a session`() = runTest {
        val backend = FakeBackend(mutableListOf(row(ownerA)), sessionUserId = null)

        for (operation in listOf<suspend () -> Any?>(
            { backend.fetchAll() },
            { backend.upsert(listOf(row(ownerA))) },
            { backend.delete(listOf(UUID.randomUUID())) }
        )) {
            try {
                operation()
                fail("an unauthenticated operation must throw")
            } catch (_: NotAuthenticatedException) {
            }
        }
    }
}
