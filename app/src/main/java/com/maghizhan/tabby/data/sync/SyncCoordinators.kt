package com.maghizhan.tabby.data.sync

import com.maghizhan.tabby.data.local.CategoryDao
import com.maghizhan.tabby.data.local.ExpenseDao
import com.maghizhan.tabby.data.local.FriendDao
import com.maghizhan.tabby.data.local.TransactionRunner
import com.maghizhan.tabby.data.local.entity.CategoryEntity
import com.maghizhan.tabby.data.local.entity.ExpenseEntity
import com.maghizhan.tabby.data.local.entity.FriendEntity
import com.maghizhan.tabby.data.remote.CategoryRepositoring
import com.maghizhan.tabby.data.remote.CompleteSnapshot
import com.maghizhan.tabby.data.remote.ExpenseRepositoring
import com.maghizhan.tabby.data.remote.FriendRepositoring
import com.maghizhan.tabby.data.remote.SessionBinding
import com.maghizhan.tabby.data.remote.SessionProvider
import com.maghizhan.tabby.data.remote.model.LocalRecord
import com.maghizhan.tabby.data.remote.model.RemoteCategoryRow
import com.maghizhan.tabby.data.remote.model.RemoteExpenseRow
import com.maghizhan.tabby.data.remote.model.RemoteFriendRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/** What a cycle actually did, so callers and tests can assert on it. */
data class SyncOutcome(
    val claimedLegacy: Int = 0,
    val pulled: Int = 0,
    val inserted: Int = 0,
    val updated: Int = 0,
    val deletedLocally: Int = 0,
    val pushed: Int = 0,
    val deletedRemotely: Int = 0,
    /** Rows whose acknowledgement was refused because they changed mid-upload. */
    val acknowledgementsSkipped: Int = 0,
    /**
     * Rows dropped because their UUID already belongs to a different account
     * locally. Non-zero means a collision was refused rather than overwriting
     * another account's record.
     */
    val collisionsRefused: Int = 0,
    /** Null when the snapshot authorised absence deletion. */
    val deletionWithheldReason: String? = null
)

/**
 * Shared sync cycle.
 *
 * Fixed order, enforced here rather than left to call sites:
 *
 *   1. bind to the live session, and claim legacy unowned rows for it
 *   2. pull a snapshot that knows whether it is provably complete
 *   3. revalidate, then apply the merge plan in ONE transaction
 *   4. revalidate before EACH remote mutation, and again before acknowledging
 *
 * Why the session is revalidated repeatedly rather than once:
 *
 * A cycle performs network I/O, and the user can sign out and into another
 * account while it is suspended. The repositories derive `user_id` from whatever
 * session is live at the moment they run, so a single up-front check lets a
 * cycle that started as A upload A's local rows under B — or acknowledge them
 * against B's local state. Each [SessionBinding.revalidate] call shrinks that
 * window to the gap between the check and the one statement that follows it.
 *
 * Why acknowledgement is compare-and-set:
 *
 * Pending rows are read, then uploaded, then marked SYNCED. An edit made during
 * the upload would be cleared by an id-and-owner-only update: the user's change
 * is never pushed, yet looks saved. Requiring the uploaded revision and state to
 * still match means a row touched meanwhile does not match, stays pending, and
 * goes up next cycle.
 */
abstract class SyncCoordinator<Entity : Any, Row : Any>(
    private val transactions: TransactionRunner,
    private val sessions: SessionProvider
) {
    /** Serializes cycles so two concurrent runs cannot interleave their plans. */
    private val cycleMutex = Mutex()

    protected abstract suspend fun claimLegacyRows(ownerId: String): Int
    protected abstract suspend fun fetchSnapshot(binding: SessionBinding): CompleteSnapshot<Row>
    protected abstract suspend fun localRecords(ownerId: String): List<Entity>
    protected abstract suspend fun pendingPush(ownerId: String): List<Entity>

    protected abstract fun idOf(entity: Entity): UUID
    protected abstract fun revisionOf(entity: Entity): Int
    protected abstract fun stateOf(entity: Entity): SyncState
    protected abstract fun toRemoteRow(entity: Entity): Row

    /**
     * Builds the merge plan from local entities.
     *
     * Takes entities rather than one shared projection because the rules differ
     * per type: category reconciliation needs name and sort order to adopt a
     * remote row created offline under the same name, which a common projection
     * would have to carry for every type that does not use it.
     */
    protected abstract fun plan(
        local: List<Entity>,
        snapshot: CompleteSnapshot<Row>,
        ownerId: String
    ): MergePlan<Row>

    protected abstract suspend fun applyInserts(rows: List<Row>, ownerId: String)
    protected abstract suspend fun applyUpdates(rows: List<Row>, ownerId: String)
    protected abstract suspend fun applyDeletions(ids: List<UUID>, ownerId: String)

    /**
     * Ids among [ids] that already belong to a DIFFERENT account locally.
     *
     * Reconciliation plans owner-filtered, but the applies land through an
     * id-only `@Upsert`, and an INSERT has no WHERE clause: a remote row whose
     * UUID collides with another account's cached row replaced it outright,
     * silently destroying that account's expense/category/friend. Collisions are
     * refused rather than merged — the colliding row is not ours to overwrite,
     * and dropping it loses nothing the backend will not re-send.
     */
    protected abstract suspend fun foreignOwnedIds(ids: List<UUID>, ownerId: String): List<UUID>

    /** The local id of a remote row, for collision checking. */
    protected abstract fun idOfRow(row: Row): UUID

    protected abstract suspend fun upsertRemote(rows: List<Row>)
    protected abstract suspend fun deleteRemote(ids: List<UUID>)

    /** CAS acknowledgement; returns rows actually updated (0 or 1). */
    protected abstract suspend fun acknowledge(
        id: UUID,
        ownerId: String,
        expectedRevision: Int,
        expectedState: SyncState
    ): Int

    /** CAS tombstone removal; returns rows actually deleted (0 or 1). */
    protected abstract suspend fun removeTombstone(
        id: UUID,
        ownerId: String,
        expectedRevision: Int
    ): Int

    /** Runs one cycle. Throws on failure, having mutated nothing before the commit. */
    suspend fun synchronize(): SyncOutcome = cycleMutex.withLock {
        val binding = SessionBinding.bind(sessions)
        val owner = binding.ownerId

        // Adopt pre-sign-in rows for this account before anything reads, so
        // every later predicate can be exact-owner.
        val claimed = transactions.inTransaction { claimLegacyRows(owner) }

        // 1. PULL - before any local mutation or upload.
        val snapshot = fetchSnapshot(binding)

        // 2. RECONCILE + APPLY atomically, against a snapshot proven to belong
        // to this exact session.
        binding.revalidate()
        val rows = snapshot.requireSession(binding)

        var inserted = 0
        var updated = 0
        var deletedLocally = 0
        var collisionsRefused = 0

        transactions.inTransaction {
            val plan = plan(
                local = localRecords(owner),
                snapshot = snapshot,
                ownerId = owner
            )

            // Collision screen, inside the same transaction as the applies: a
            // check performed outside it could be invalidated before the upsert
            // runs. Reconciliation plans owner-filtered, but the applies go
            // through an id-only @Upsert, so without this a remote UUID that
            // collides with another account's cached row replaces it.
            val candidateIds = (plan.inserts + plan.updates).map(::idOfRow).distinct()
            val foreign = if (candidateIds.isEmpty()) {
                emptySet()
            } else {
                foreignOwnedIds(candidateIds, owner).toSet()
            }
            val inserts = plan.inserts.filterNot { idOfRow(it) in foreign }
            val updates = plan.updates.filterNot { idOfRow(it) in foreign }
            collisionsRefused =
                (plan.inserts.size - inserts.size) + (plan.updates.size - updates.size)

            if (inserts.isNotEmpty()) {
                applyInserts(inserts, owner)
                inserted = inserts.size
            }
            if (updates.isNotEmpty()) {
                applyUpdates(updates, owner)
                updated = updates.size
            }
            if (plan.deletions.isNotEmpty()) {
                applyDeletions(plan.deletions, owner)
                deletedLocally = plan.deletions.size
            }
        }

        // 3. PUSH - the local store now reflects the backend.
        val pending = pendingPush(owner)
        val tombstones = pending.filter { stateOf(it) == SyncState.DELETED }
        val upserts = pending.filter { stateOf(it) != SyncState.DELETED }

        var pushed = 0
        var skipped = 0

        if (upserts.isNotEmpty()) {
            // Revalidated immediately before the remote write: the repository
            // stamps user_id from the live session, so a switch here would
            // upload this account's rows under the next one.
            binding.revalidate()
            upsertRemote(upserts.map(::toRemoteRow))

            binding.revalidate()
            transactions.inTransaction {
                for (entity in upserts) {
                    val affected = acknowledge(
                        id = idOf(entity),
                        ownerId = owner,
                        expectedRevision = revisionOf(entity),
                        expectedState = stateOf(entity)
                    )
                    if (affected == 0) skipped++ else pushed++
                }
            }
        }

        var deletedRemotely = 0
        if (tombstones.isNotEmpty()) {
            binding.revalidate()
            deleteRemote(tombstones.map(::idOf))

            binding.revalidate()
            transactions.inTransaction {
                for (entity in tombstones) {
                    // Only removed if still the exact tombstone that was deleted
                    // remotely; a row resurrected mid-delete must survive.
                    val affected = removeTombstone(
                        id = idOf(entity),
                        ownerId = owner,
                        expectedRevision = revisionOf(entity)
                    )
                    if (affected == 0) skipped++ else deletedRemotely++
                }
            }
        }

        SyncOutcome(
            claimedLegacy = claimed,
            pulled = rows.size,
            inserted = inserted,
            updated = updated,
            deletedLocally = deletedLocally,
            pushed = pushed,
            deletedRemotely = deletedRemotely,
            acknowledgementsSkipped = skipped,
            collisionsRefused = collisionsRefused,
            deletionWithheldReason = snapshot.unprovenReason
        )
    }

    /**
     * Runs a cycle, reporting failure as null rather than throwing, for callers
     * (app start, pull-to-refresh) where a failed sync is not something the user
     * must act on. Cancellation still propagates.
     */
    suspend fun synchronizeQuietly(): SyncOutcome? = try {
        synchronize()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Throwable) {
        null
    }
}

/** A merge plan, shared across entity types. */
data class MergePlan<Row>(
    val inserts: List<Row> = emptyList(),
    val updates: List<Row> = emptyList(),
    val deletions: List<UUID> = emptyList()
)

class ExpenseSyncCoordinator(
    private val dao: ExpenseDao,
    private val repository: ExpenseRepositoring,
    transactions: TransactionRunner,
    sessions: SessionProvider
) : SyncCoordinator<ExpenseEntity, RemoteExpenseRow>(transactions, sessions) {

    override suspend fun claimLegacyRows(ownerId: String) = dao.claimLegacyRows(ownerId)
    override suspend fun fetchSnapshot(binding: SessionBinding) = repository.fetchAll(binding)
    override suspend fun localRecords(ownerId: String) = dao.allForOwner(ownerId)
    override suspend fun pendingPush(ownerId: String) = dao.pendingPush(ownerId)

    override fun idOf(entity: ExpenseEntity) = entity.id
    override fun revisionOf(entity: ExpenseEntity) = entity.revision
    override fun stateOf(entity: ExpenseEntity) = entity.syncState

    override fun toRemoteRow(entity: ExpenseEntity) = RemoteExpenseRow(
        id = entity.id,
        // Overwritten with the session's id by the repository; never trusted here.
        userId = entity.ownerId.orEmpty(),
        amount = MoneyValidation.normalizedAmount(entity.amount, "amount"),
        categoryName = entity.categoryName,
        note = entity.note,
        date = entity.date,
        createdAt = entity.createdAt,
        updatedAt = entity.updatedAt
    )

    override fun plan(
        local: List<ExpenseEntity>,
        snapshot: CompleteSnapshot<RemoteExpenseRow>,
        ownerId: String
    ): MergePlan<RemoteExpenseRow> {
        val p = ExpenseReconciliation.plan(
            local.map {
                LocalRecord(
                    id = it.id,
                    ownerId = it.ownerId,
                    updatedAt = it.updatedAt,
                    syncState = it.syncState,
                    hasRemoteIdentity = it.remoteId != null
                )
            },
            snapshot,
            ownerId
        )
        return MergePlan(p.inserts, p.updates.map { it.row }, p.deletions)
    }

    override suspend fun applyInserts(rows: List<RemoteExpenseRow>, ownerId: String) =
        dao.upsert(rows.map { it.toEntity(ownerId) })

    override suspend fun applyUpdates(rows: List<RemoteExpenseRow>, ownerId: String) {
        val existing = dao.allForOwner(ownerId).associateBy { it.id }
        dao.upsert(rows.map { it.toEntity(ownerId, existing[it.id]?.revision ?: 0) })
    }

    override suspend fun applyDeletions(ids: List<UUID>, ownerId: String) =
        dao.deleteByIds(ids, ownerId)

    override suspend fun foreignOwnedIds(ids: List<UUID>, ownerId: String) =
        dao.foreignOwnedIds(ids, ownerId)

    override fun idOfRow(row: RemoteExpenseRow): UUID = row.id

    override suspend fun upsertRemote(rows: List<RemoteExpenseRow>) = repository.upsert(rows)
    override suspend fun deleteRemote(ids: List<UUID>) = repository.delete(ids)

    override suspend fun acknowledge(
        id: UUID,
        ownerId: String,
        expectedRevision: Int,
        expectedState: SyncState
    ) = dao.markSyncedIfUnchanged(id, ownerId, expectedRevision, expectedState.raw)

    override suspend fun removeTombstone(id: UUID, ownerId: String, expectedRevision: Int) =
        dao.deleteTombstoneIfUnchanged(id, ownerId, expectedRevision)
}

class CategorySyncCoordinator(
    private val dao: CategoryDao,
    private val repository: CategoryRepositoring,
    transactions: TransactionRunner,
    sessions: SessionProvider
) : SyncCoordinator<CategoryEntity, RemoteCategoryRow>(transactions, sessions) {

    override suspend fun claimLegacyRows(ownerId: String) = dao.claimLegacyRows(ownerId)
    override suspend fun fetchSnapshot(binding: SessionBinding) = repository.fetchAll(binding)
    override suspend fun localRecords(ownerId: String) = dao.allForOwner(ownerId)
    override suspend fun pendingPush(ownerId: String) = dao.pendingPush(ownerId)

    override fun idOf(entity: CategoryEntity) = entity.id
    override fun revisionOf(entity: CategoryEntity) = entity.revision
    override fun stateOf(entity: CategoryEntity) = entity.syncState

    override fun toRemoteRow(entity: CategoryEntity) = RemoteCategoryRow(
        id = entity.id,
        userId = entity.ownerId.orEmpty(),
        name = entity.name,
        isDefault = entity.isDefault,
        sortOrder = entity.sortOrder
    )

    override fun plan(
        local: List<CategoryEntity>,
        snapshot: CompleteSnapshot<RemoteCategoryRow>,
        ownerId: String
    ): MergePlan<RemoteCategoryRow> {
        val p = CategoryReconciliation.plan(
            local.map {
                CategoryReconciliation.LocalRecord(
                    id = it.id,
                    ownerId = it.ownerId,
                    name = it.name,
                    sortOrder = it.sortOrder,
                    isDefault = it.isDefault,
                    syncState = it.syncState,
                    hasRemoteIdentity = it.remoteId != null
                )
            },
            snapshot,
            ownerId
        )
        return MergePlan(p.inserts, p.updates, p.deletions)
    }

    override suspend fun applyInserts(rows: List<RemoteCategoryRow>, ownerId: String) =
        dao.upsert(rows.map { it.toEntity(ownerId) })

    override suspend fun applyUpdates(rows: List<RemoteCategoryRow>, ownerId: String) =
        dao.upsert(rows.map { it.toEntity(ownerId) })

    override suspend fun applyDeletions(ids: List<UUID>, ownerId: String) =
        dao.deleteByIds(ids, ownerId)

    override suspend fun foreignOwnedIds(ids: List<UUID>, ownerId: String) =
        dao.foreignOwnedIds(ids, ownerId)

    override fun idOfRow(row: RemoteCategoryRow): UUID = row.id

    override suspend fun upsertRemote(rows: List<RemoteCategoryRow>) = repository.upsert(rows)
    override suspend fun deleteRemote(ids: List<UUID>) = repository.delete(ids)

    override suspend fun acknowledge(
        id: UUID,
        ownerId: String,
        expectedRevision: Int,
        expectedState: SyncState
    ) = dao.markSyncedIfUnchanged(id, ownerId, expectedRevision, expectedState.raw)

    override suspend fun removeTombstone(id: UUID, ownerId: String, expectedRevision: Int) =
        dao.deleteTombstoneIfUnchanged(id, ownerId, expectedRevision)
}

class FriendSyncCoordinator(
    private val dao: FriendDao,
    private val repository: FriendRepositoring,
    transactions: TransactionRunner,
    sessions: SessionProvider
) : SyncCoordinator<FriendEntity, RemoteFriendRow>(transactions, sessions) {

    override suspend fun claimLegacyRows(ownerId: String) = dao.claimLegacyRows(ownerId)
    override suspend fun fetchSnapshot(binding: SessionBinding) = repository.fetchAll(binding)
    override suspend fun localRecords(ownerId: String) = dao.allForOwner(ownerId)
    override suspend fun pendingPush(ownerId: String) = dao.pendingPush(ownerId)

    override fun idOf(entity: FriendEntity) = entity.id
    override fun revisionOf(entity: FriendEntity) = entity.revision
    override fun stateOf(entity: FriendEntity) = entity.syncState

    override fun toRemoteRow(entity: FriendEntity) = RemoteFriendRow(
        id = entity.id,
        userId = entity.ownerId.orEmpty(),
        name = entity.name,
        theyOweUs = MoneyValidation.normalizedBalance(entity.theyOweUs, "they_owe_us"),
        weOweThem = MoneyValidation.normalizedBalance(entity.weOweThem, "we_owe_them"),
        createdAt = entity.createdAt,
        updatedAt = entity.updatedAt
    )

    override fun plan(
        local: List<FriendEntity>,
        snapshot: CompleteSnapshot<RemoteFriendRow>,
        ownerId: String
    ): MergePlan<RemoteFriendRow> {
        val p = FriendReconciliation.plan(
            local.map {
                LocalRecord(
                    id = it.id,
                    ownerId = it.ownerId,
                    updatedAt = it.updatedAt,
                    syncState = it.syncState,
                    hasRemoteIdentity = it.remoteId != null
                )
            },
            snapshot,
            ownerId
        )
        return MergePlan(p.inserts, p.updates.map { it.row }, p.deletions)
    }

    override suspend fun applyInserts(rows: List<RemoteFriendRow>, ownerId: String) =
        dao.upsert(rows.map { it.toEntity(ownerId) })

    override suspend fun applyUpdates(rows: List<RemoteFriendRow>, ownerId: String) {
        val existing = dao.allForOwner(ownerId).associateBy { it.id }
        dao.upsert(rows.map { it.toEntity(ownerId, existing[it.id]?.revision ?: 0) })
    }

    override suspend fun applyDeletions(ids: List<UUID>, ownerId: String) =
        dao.deleteByIds(ids, ownerId)

    override suspend fun foreignOwnedIds(ids: List<UUID>, ownerId: String) =
        dao.foreignOwnedIds(ids, ownerId)

    override fun idOfRow(row: RemoteFriendRow): UUID = row.id

    override suspend fun upsertRemote(rows: List<RemoteFriendRow>) = repository.upsert(rows)
    override suspend fun deleteRemote(ids: List<UUID>) = repository.delete(ids)

    override suspend fun acknowledge(
        id: UUID,
        ownerId: String,
        expectedRevision: Int,
        expectedState: SyncState
    ) = dao.markSyncedIfUnchanged(id, ownerId, expectedRevision, expectedState.raw)

    override suspend fun removeTombstone(id: UUID, ownerId: String, expectedRevision: Int) =
        dao.deleteTombstoneIfUnchanged(id, ownerId, expectedRevision)
}

private fun RemoteExpenseRow.toEntity(ownerId: String, revision: Int = 0) = ExpenseEntity(
    id = id,
    amount = MoneyValidation.normalizedAmount(amount, "amount"),
    categoryName = categoryName,
    note = ExpenseEntity.normalizedNote(note),
    date = date,
    createdAt = createdAt,
    updatedAt = updatedAt,
    syncStateRaw = SyncState.SYNCED.raw,
    remoteId = id.toString(),
    ownerId = ownerId,
    revision = revision
)

private fun RemoteCategoryRow.toEntity(ownerId: String) = CategoryEntity(
    id = id,
    name = name,
    isDefault = isDefault,
    sortOrder = sortOrder,
    syncStateRaw = SyncState.SYNCED.raw,
    remoteId = id.toString(),
    ownerId = ownerId
)

private fun RemoteFriendRow.toEntity(ownerId: String, revision: Int = 0) = FriendEntity(
    id = id,
    name = name,
    ownerId = ownerId,
    theyOweUs = MoneyValidation.normalizedBalance(theyOweUs, "they_owe_us"),
    weOweThem = MoneyValidation.normalizedBalance(weOweThem, "we_owe_them"),
    createdAt = createdAt,
    updatedAt = updatedAt,
    syncStateRaw = SyncState.SYNCED.raw,
    remoteId = id.toString(),
    revision = revision
)
