package com.fesu.renjana.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fesu.renjana.RenjanaApplication
import com.fesu.renjana.models.AppInfo
import com.fesu.renjana.utils.RenjanaLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class AppsViewModel : ViewModel() {
    companion object {
        private const val TAG = "AppsViewModel"
    }

    private val appManager = RenjanaApplication.get().appManager

    private val _apps = MutableStateFlow<List<AppInfo>>(emptyList())
    val apps: StateFlow<List<AppInfo>> = _apps

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    init {
        loadApps()
    }

    private fun loadApps() {
        RenjanaLog.d(TAG, "Loading apps")
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                val result = appManager.getInstalledApps()
                _apps.value = result
                RenjanaLog.d(TAG, "Loaded ${result.size} apps")
            } catch (e: Exception) {
                RenjanaLog.e(TAG, "Failed to load apps: ${e.message}")
                _error.value = e.message ?: "Unknown error"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun refresh() {
        loadApps()
    }
}
