package com.maghizhan.tabby.data.remote

/**
 * The authenticated account a unit of work is bound to.
 *
 * [generation] is what makes an account switch *detectable*. The owner id alone
 * is not enough: sign out of account A, sign into B, and back into A, and the
 * owner id is identical again even though every token in between changed. The
 * generation increments on each such transition, so work started under the first
 * A session cannot silently finish under the second.
 */
data class ActiveSession(val ownerId: String, val generation: Long)

/** Reads the live authenticated session. */
interface SessionProvider {
    suspend fun current(): ActiveSession?
}

/** Raised when the authenticated session changed under an in-flight operation. */
class SessionChangedException(expected: ActiveSession, actual: ActiveSession?) : Exception(
    "Session changed mid-operation: bound to generation ${expected.generation}, " +
        "now ${actual?.generation?.toString() ?: "signed out"}."
)

/**
 * A sync cycle pinned to one session, re-checkable at every dangerous step.
 *
 * Validating once at the start of a cycle is not enough. A cycle performs
 * network I/O, and the user can sign out and into a different account while it
 * is suspended. Without a re-check the next step derives the *new* session's
 * owner and happily uploads the previous account's rows under it, or clears the
 * new account's pending state. Both are silent cross-account corruption, which
 * is why [revalidate] is called immediately before each remote mutation and
 * before each local acknowledgement rather than once up front.
 */
class SessionBinding(
    val session: ActiveSession,
    private val provider: SessionProvider
) {
    val ownerId: String get() = session.ownerId

    /**
     * Throws unless the live session is still exactly the one this binding was
     * created for. Call it as late as possible before any side effect — the
     * point is to shrink the window between the check and the write.
     */
    suspend fun revalidate() {
        val now = provider.current()
        if (now != session) throw SessionChangedException(session, now)
    }

    companion object {
        /** Binds to whatever session is live now, or throws when signed out. */
        suspend fun bind(provider: SessionProvider): SessionBinding {
            val session = provider.current() ?: throw NotAuthenticatedException()
            return SessionBinding(session, provider)
        }
    }
}
