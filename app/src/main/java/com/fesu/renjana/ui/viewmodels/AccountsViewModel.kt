package com.fesu.renjana.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fesu.renjana.RenjanaApplication
import com.fesu.renjana.gms.GoogleSignInVirtualizer
import com.fesu.renjana.models.GoogleAccount
import com.fesu.renjana.utils.RenjanaLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

class AccountsViewModel : ViewModel() {
    companion object {
        private const val TAG = "AccountsViewModel"
    }

    private val accountManager = RenjanaApplication.get().googleAccountManager

    private val _accounts = MutableStateFlow<List<GoogleAccount>>(emptyList())
    val accounts: StateFlow<List<GoogleAccount>> = _accounts.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        loadAccounts()
    }

    /**
     * Load all Google accounts from the database via [GoogleAccountManager].
     * Collects the Flow reactively so the UI updates whenever the underlying
     * data changes.
     */
    private fun loadAccounts() {
        RenjanaLog.d(TAG, "Loading accounts")
        viewModelScope.launch {
            _isLoading.value = true
            try {
                accountManager.getAllAccounts()
                    .catch { e ->
                        RenjanaLog.e(TAG, "Failed to load accounts: ${e.message}")
                        _error.value = e.message ?: "Failed to load accounts"
                        _isLoading.value = false
                    }
                    .collect { list ->
                        _accounts.value = list
                        RenjanaLog.d(TAG, "Loaded ${list.size} accounts")
                        _isLoading.value = false
                    }
            } catch (e: Exception) {
                RenjanaLog.e(TAG, "Failed to load accounts: ${e.message}")
                _error.value = e.message ?: "Failed to load accounts"
                _isLoading.value = false
            }
        }
    }

    /**
     * Assign a Google account to a container instance.
     *
     * Delegates to [GoogleSignInVirtualizer.assignAccountToInstance] which
     * persists the mapping and invalidates any cached virtual account.
     *
     * @param instanceId Unique identifier of the container instance
     * @param accountId ID of the Google account to assign
     */
    fun assignAccountToInstance(instanceId: String, accountId: String) {
        RenjanaLog.i(TAG, "Assigning account $accountId to instance $instanceId")
        viewModelScope.launch {
            try {
                val virtualizer = ensureVirtualizer()
                val result = virtualizer.assignAccountToInstance(instanceId, accountId)
                if (result.isFailure) {
                    val cause = result.exceptionOrNull()?.message ?: "Unknown error"
                    RenjanaLog.e(TAG, "Failed to assign account: $cause")
                    _error.value = "Failed to assign account: $cause"
                } else {
                    RenjanaLog.i(TAG, "Account $accountId assigned to instance $instanceId")
                }
            } catch (e: Exception) {
                RenjanaLog.e(TAG, "Failed to assign account to instance: ${e.message}")
                _error.value = e.message ?: "Failed to assign account"
            }
        }
    }

    /**
     * Remove the account assignment from a container instance (sign-out).
     *
     * Delegates to [GoogleSignInVirtualizer.removeAccountFromInstance] which
     * clears the mapping and revokes cached tokens.
     *
     * @param instanceId Unique identifier of the container instance
     */
    fun removeAccountFromInstance(instanceId: String) {
        RenjanaLog.i(TAG, "Removing account from instance $instanceId")
        viewModelScope.launch {
            try {
                val virtualizer = ensureVirtualizer()
                virtualizer.removeAccountFromInstance(instanceId)
                RenjanaLog.i(TAG, "Account removed from instance $instanceId")
            } catch (e: Exception) {
                RenjanaLog.e(TAG, "Failed to remove account from instance: ${e.message}")
                _error.value = e.message ?: "Failed to remove account"
            }
        }
    }

    /**
     * Delete a Google account from the database entirely.
     */
    fun deleteAccount(accountId: String) {
        RenjanaLog.i(TAG, "Deleting account: $accountId")
        viewModelScope.launch {
            try {
                val result = accountManager.removeAccount(accountId)
                if (result.isFailure) {
                    val cause = result.exceptionOrNull()?.message ?: "Unknown error"
                    RenjanaLog.e(TAG, "Failed to delete account: $cause")
                    _error.value = "Failed to delete account: $cause"
                } else {
                    RenjanaLog.i(TAG, "Account deleted: $accountId")
                }
            } catch (e: Exception) {
                RenjanaLog.e(TAG, "Failed to delete account: ${e.message}")
                _error.value = e.message ?: "Failed to delete account"
            }
        }
    }

    /**
     * Clear the current error state.
     */
    fun clearError() {
        _error.value = null
    }

    /**
     * Ensure the [GoogleSignInVirtualizer] singleton is initialized and return it.
     * Initializes lazily from the application context if needed.
     */
    private fun ensureVirtualizer(): GoogleSignInVirtualizer {
        if (!GoogleSignInVirtualizer.isInitialized()) {
            GoogleSignInVirtualizer.init(RenjanaApplication.get())
        }
        return GoogleSignInVirtualizer.get()
    }
}
