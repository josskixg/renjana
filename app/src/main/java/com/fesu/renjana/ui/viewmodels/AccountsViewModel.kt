package com.fesu.renjana.ui.viewmodels

import androidx.lifecycle.ViewModel
import com.fesu.renjana.RenjanaApplication
import com.fesu.renjana.models.GoogleAccount
import com.fesu.renjana.utils.RenjanaLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class AccountsViewModel : ViewModel() {
    companion object {
        private const val TAG = "AccountsViewModel"
    }

    private val accountManager = RenjanaApplication.get().googleAccountManager

    private val _accounts = MutableStateFlow<List<GoogleAccount>>(emptyList())
    val accounts: StateFlow<List<GoogleAccount>> = _accounts

    init {
        loadAccounts()
    }

    private fun loadAccounts() {
        RenjanaLog.d(TAG, "Loading accounts")
        // TODO: Implement account loading
    }

    fun deleteAccount(accountId: String) {
        RenjanaLog.i(TAG, "Deleting account: $accountId")
        // TODO: Implement account deletion
    }
}
