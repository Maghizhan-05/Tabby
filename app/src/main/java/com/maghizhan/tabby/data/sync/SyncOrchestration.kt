package com.maghizhan.tabby.data.sync

import com.maghizhan.tabby.data.remote.CompleteSnapshot

/**
 * The ordering half of the sync contract, separated from the merge rules so it
 * can be tested without Room or a network.
 *
 * The iOS `SyncEngine` resolves each entity as `pull -> reconcile -> push`, and
 * that order is the fix for the expense-resurrection bug: pushing first
 * re-uploads a local row that another device deleted, after which the next
 * snapshot contains it again and the deletion is lost permanently. Encoding the
 * order here — rather than trusting each call site to remember it — makes the
 * sequence testable and keeps the two platforms honest with each other.
 *
 * Errors abort: a failed fetch must never fall through to a push, because the
 * reconciliation that a push depends on has not happened.
 */
object SyncOrchestration {

    /** Recorded step order, so a test can assert the sequence rather than the outcome. */
    enum class Step { FETCH, RECONCILE, PUSH }

    data class Result<P>(val plan: P, val steps: List<Step>)

    /**
     * Runs one entity's cycle in the required order.
     *
     * @param fetch obtains a provably complete snapshot.
     * @param reconcile merges it against the local store, producing a plan.
     * @param push applies the plan's local work to the backend.
     */
    suspend fun <R, P> synchronize(
        fetch: suspend () -> CompleteSnapshot<R>,
        reconcile: suspend (CompleteSnapshot<R>) -> P,
        push: suspend (P) -> Unit
    ): Result<P> {
        val steps = mutableListOf<Step>()

        steps += Step.FETCH
        val snapshot = fetch()

        steps += Step.RECONCILE
        val plan = reconcile(snapshot)

        steps += Step.PUSH
        push(plan)

        return Result(plan, steps.toList())
    }
}
