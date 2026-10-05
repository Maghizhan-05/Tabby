package com.maghizhan.tabby.data.remote

import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.flow.first
import java.util.concurrent.atomic.AtomicLong

/**
 * Supabase-backed [SessionProvider] that assigns each distinct session a
 * monotonic generation.
 *
 * The owner id alone cannot identify a session: sign out of A, into B, and back
 * into A, and the id is identical again although every token in between changed.
 * Work bound to the first A session would then be indistinguishable from work
 * bound to the second and could be applied across the gap. The generation
 * increments whenever the observed session identity changes, so that comparison
 * fails as it should.
 *
 * The access token (not the user id) is the identity signal, because it changes
 * on every sign-in, sign-out and refresh. A refresh therefore also bumps the
 * generation, which is deliberately conservative: it aborts an in-flight cycle
 * that spans a token refresh rather than risking a write under an identity we
 * did not verify. The cycle simply runs again.
 */
class SupabaseSessionProvider : SessionProvider {

    private val counter = AtomicLong(0)

    @Volatile
    private var lastToken: String? = null

    @Volatile
    private var lastSession: ActiveSession? = null

    override suspend fun current(): ActiveSession? {
        val auth = client()?.auth ?: return null

        // Wait out Initializing so a session still loading from storage is not
        // misread as "signed out" — the same reason the router has a RESTORING
        // state at all.
        val status = auth.sessionStatus.first { it !is SessionStatus.Initializing }
        val authenticated = status as? SessionStatus.Authenticated ?: run {
            lastToken = null
            lastSession = null
            return null
        }

        val userId = authenticated.session.user?.id ?: return null
        val token = authenticated.session.accessToken

        return synchronized(this) {
            val cached = lastSession
            if (cached != null && token == lastToken && cached.ownerId == userId) {
                cached
            } else {
                lastToken = token
                ActiveSession(ownerId = userId, generation = counter.incrementAndGet())
                    .also { lastSession = it }
            }
        }
    }

    private fun client() = SupabaseClientProvider.client
}
