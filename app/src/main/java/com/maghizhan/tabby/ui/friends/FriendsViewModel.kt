package com.maghizhan.tabby.ui.friends

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.maghizhan.tabby.data.local.FriendStore
import com.maghizhan.tabby.data.local.entity.FriendEntity
import com.maghizhan.tabby.data.sync.Ownership
import com.maghizhan.tabby.data.sync.SyncState
import com.maghizhan.tabby.analytics.AnalyticsEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/** The friends editor's fields and transient state. */
data class FriendEditState(
    val friendId: UUID? = null,
    val name: String = "",
    val theyOweUsText: String = "0",
    val weOweThemText: String = "0",
    val notice: String? = null,
    val isPresenting: Boolean = false,
    val isSaving: Boolean = false
) {
    val isEditing: Boolean get() = friendId != null

    val canSave: Boolean
        get() = !isSaving &&
            name.trim().isNotEmpty() &&
            FriendsForm.parseBalance(theyOweUsText) != null &&
            FriendsForm.parseBalance(weOweThemText) != null
}

/**
 * Pure friend-form rules, ported from `FriendsViewModel.swift`.
 *
 * Separate from the view model so the balance parsing, visibility filter and
 * aggregate arithmetic are testable without a Room database or a coroutine
 * scope — the same split the data layer already uses for `Ownership`.
 */
object FriendsForm {

    /**
     * Parses a balance, or null when unusable.
     *
     * Negative values are rejected: a balance is "how much is owed", and the
     * direction is carried by *which* of the two columns holds it. A negative
     * `theyOweUs` would double-count against `netBalance`. This mirrors
     * `MoneyValidation.normalizedBalance`, checked here so the Save button is
     * disabled rather than the store throwing at commit time.
     */
    fun parseBalance(text: String): BigDecimal? {
        val cleaned = text.trim().replace(',', '.')
        if (cleaned.isEmpty()) return null
        val value = runCatching { BigDecimal(cleaned) }.getOrNull() ?: return null
        if (value.signum() < 0) return null
        if (value.stripTrailingZeros().scale() > 2) return null
        return value
    }

    /**
     * Rows the signed-in account may see: never another account's, never a
     * tombstone, and nothing at all while signed out.
     *
     * Owner comparison is normalised (case/whitespace-insensitive) because a
     * locally created row carries the session's id as given while a pulled row
     * carries Postgres's canonical lowercase form — a raw `==` would hide every
     * synced friend.
     */
    fun visibleFriends(friends: List<FriendEntity>, activeOwnerId: String?): List<FriendEntity> {
        if (Ownership.normalized(activeOwnerId) == null) return emptyList()
        return friends.filter {
            it.syncState != SyncState.DELETED &&
                Ownership.isAccessible(it.ownerId, activeOwnerId)
        }
    }

    /** Sum of net balances; positive means more is owed to the user than by them. */
    fun aggregateNet(friends: List<FriendEntity>): BigDecimal =
        friends
            .filter { it.syncState != SyncState.DELETED }
            .fold(BigDecimal.ZERO) { sum, friend -> sum.add(friend.netBalance) }
}

class FriendsViewModel(
    private val friendStore: FriendStore,
    private val onLocalWrite: suspend () -> Unit,
    private val onAnalytics: suspend (AnalyticsEvent) -> Unit = {},
    private val now: () -> Instant = Instant::now
) : ViewModel() {

    private val _editState = MutableStateFlow(FriendEditState())
    val editState: StateFlow<FriendEditState> = _editState.asStateFlow()

    fun beginAdding() {
        _editState.value = FriendEditState(isPresenting = true)
    }

    fun beginEditing(friend: FriendEntity) {
        _editState.value = FriendEditState(
            friendId = friend.id,
            name = friend.name,
            theyOweUsText = friend.theyOweUs.toPlainString(),
            weOweThemText = friend.weOweThem.toPlainString(),
            isPresenting = true
        )
    }

    fun dismissEditor() = _editState.update { it.copy(isPresenting = false, notice = null) }

    fun onNameChanged(value: String) = _editState.update { it.copy(name = value, notice = null) }

    fun onTheyOweUsChanged(value: String) =
        _editState.update { it.copy(theyOweUsText = value, notice = null) }

    fun onWeOweThemChanged(value: String) =
        _editState.update { it.copy(weOweThemText = value, notice = null) }

    /**
     * Saves the editor's contents.
     *
     * [existing] is the live row being edited, looked up by the screen from its
     * observed list — passed in rather than cached at `beginEditing` time so a
     * row that changed under the open sheet (a pull landing mid-edit) is updated
     * from its current revision, not a stale snapshot.
     */
    fun save(existing: FriendEntity?, activeOwnerId: String?) {
        val state = _editState.value
        val trimmedName = state.name.trim()
        val theyOweUs = FriendsForm.parseBalance(state.theyOweUsText)
        val weOweThem = FriendsForm.parseBalance(state.weOweThemText)

        if (trimmedName.isEmpty() || theyOweUs == null || weOweThem == null) {
            _editState.update { it.copy(notice = "Enter a name and valid, non-negative balances.") }
            return
        }
        val owner = Ownership.normalized(activeOwnerId)
        if (owner == null) {
            _editState.update { it.copy(notice = "Sign in to save friends.") }
            return
        }
        if (state.isSaving) return

        _editState.update { it.copy(isSaving = true, notice = null) }

        viewModelScope.launch {
            try {
                val timestamp = now()
                val entity = existing?.copy(
                    name = trimmedName,
                    theyOweUs = theyOweUs,
                    weOweThem = weOweThem,
                    updatedAt = timestamp,
                    syncStateRaw = if (existing.syncState == SyncState.SYNCED) {
                        SyncState.DIRTY.raw
                    } else {
                        existing.syncStateRaw
                    }
                ) ?: FriendEntity(
                    id = UUID.randomUUID(),
                    name = trimmedName,
                    ownerId = owner,
                    theyOweUs = theyOweUs,
                    weOweThem = weOweThem,
                    createdAt = timestamp,
                    updatedAt = timestamp,
                    syncStateRaw = SyncState.LOCAL.raw
                )

                friendStore.save(entity, owner)
                onAnalytics(if (existing == null) AnalyticsEvent.FriendCreated else AnalyticsEvent.FriendUpdated)
                _editState.value = FriendEditState()
                onLocalWrite()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                _editState.update {
                    it.copy(isSaving = false, notice = error.message ?: "Could not save. Try again.")
                }
            }
        }
    }

    /**
     * Tombstones a friend rather than deleting it.
     *
     * `FriendStore.markDeleted` keeps the row as a DELETED tombstone until the
     * backend confirms, so a deletion made offline is retried instead of lost —
     * and a row erased locally first would simply reappear on the next pull.
     */
    companion object {
        fun factory(
            friendStore: FriendStore,
            onLocalWrite: suspend () -> Unit,
            onAnalytics: suspend (AnalyticsEvent) -> Unit = {}
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { FriendsViewModel(friendStore, onLocalWrite, onAnalytics = onAnalytics) }
        }
    }

    fun delete(friend: FriendEntity, activeOwnerId: String?) {
        val owner = Ownership.normalized(activeOwnerId) ?: return
        if (!Ownership.isAccessible(friend.ownerId, owner)) return

        viewModelScope.launch {
            try {
                friendStore.markDeleted(friend.copy(updatedAt = now()), owner)
                onLocalWrite()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                _editState.update {
                    it.copy(notice = error.message ?: "Could not delete. Try again.")
                }
            }
        }
    }
}
