package com.maghizhan.tabby.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.maghizhan.tabby.account.AccountDeletionCoordinator
import com.maghizhan.tabby.account.AccountDeletionRequestStore
import com.maghizhan.tabby.data.remote.AuthProvider
import com.maghizhan.tabby.data.remote.AuthServicing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AccountDeletionUiState(
    val isPresented: Boolean = false,
    val password: String = "",
    val isBusy: Boolean = false,
    val notice: String? = null
)

class AccountDeletionViewModel(
    private val provider: AuthProvider,
    private val auth: AuthServicing,
    private val coordinator: AccountDeletionCoordinator,
    private val pendingGoogle: AccountDeletionRequestStore,
    private val beginGoogleReauthentication: () -> Unit,
    private val onDeleted: () -> Unit
) : ViewModel() {
    private val _state = MutableStateFlow(AccountDeletionUiState())
    val state: StateFlow<AccountDeletionUiState> = _state.asStateFlow()

    fun present() { _state.value = AccountDeletionUiState(isPresented = true) }
    fun dismiss() { if (!_state.value.isBusy) _state.value = AccountDeletionUiState() }
    fun onPasswordChanged(value: String) = _state.update { it.copy(password = value, notice = null) }

    fun confirm() {
        if (_state.value.isBusy) return
        _state.update { it.copy(isBusy = true, notice = null) }
        viewModelScope.launch {
            try {
                when (provider) {
                    AuthProvider.EMAIL -> {
                        if (_state.value.password.isBlank()) {
                            _state.update { it.copy(isBusy = false, notice = "Enter your password to continue.") }
                            return@launch
                        }
                        auth.reauthenticateEmail(_state.value.password)
                        deleteNow()
                    }
                    AuthProvider.GOOGLE -> {
                        pendingGoogle.begin()
                        beginGoogleReauthentication()
                        _state.update { it.copy(isBusy = false) }
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                _state.update {
                    it.copy(isBusy = false, notice = "We could not verify or delete this account. Try again.")
                }
            }
        }
    }


    private suspend fun deleteNow() {
        coordinator.deleteImmediately()
        pendingGoogle.clear()
        _state.value = AccountDeletionUiState()
        onDeleted()
    }

    companion object {
        fun factory(
            provider: AuthProvider,
            auth: AuthServicing,
            coordinator: AccountDeletionCoordinator,
            pendingGoogle: AccountDeletionRequestStore,
            beginGoogleReauthentication: () -> Unit,
            onDeleted: () -> Unit
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                AccountDeletionViewModel(
                    provider, auth, coordinator, pendingGoogle,
                    beginGoogleReauthentication, onDeleted
                )
            }
        }
    }
}
