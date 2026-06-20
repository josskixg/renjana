package com.fesu.renjana.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fesu.renjana.RenjanaApplication
import com.fesu.renjana.core.InstanceLifecycleService
import com.fesu.renjana.models.Instance
import com.fesu.renjana.utils.RenjanaLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class HomeViewModel : ViewModel() {
    companion object {
        private const val TAG = "HomeViewModel"
    }

    private val instanceManager = RenjanaApplication.get().instanceManager
    private val instanceLauncher = RenjanaApplication.get().instanceLauncher

    private val _instances = MutableStateFlow<List<Instance>>(emptyList())
    val instances: StateFlow<List<Instance>> = _instances.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        loadInstances()
    }

    private fun loadInstances() {
        RenjanaLog.d(TAG, "Loading instances from database")
        viewModelScope.launch {
            _isLoading.value = true
            try {
                instanceManager.getAllInstances().collect { list ->
                    _instances.value = list
                    RenjanaLog.d(TAG, "Loaded ${list.size} instances")
                    _isLoading.value = false
                }
            } catch (e: Exception) {
                RenjanaLog.e(TAG, "Failed to load instances: ${e.message}")
                _error.value = e.message ?: "Failed to load instances"
                _isLoading.value = false
            }
        }
    }

    fun deleteInstance(instanceId: String) {
        RenjanaLog.i(TAG, "Deleting instance: $instanceId")
        viewModelScope.launch {
            try {
                instanceManager.deleteInstance(instanceId)
            } catch (e: Exception) {
                RenjanaLog.e(TAG, "Failed to delete instance: ${e.message}")
                _error.value = e.message ?: "Failed to delete instance"
            }
        }
    }

    fun launchInstance(instanceId: String) {
        viewModelScope.launch {
            try {
                val success = instanceLauncher.launchInstance(instanceId)
                if (!success) {
                    _error.value = "Failed to launch instance. Check Error Logs for details."
                }
            } catch (e: Exception) {
                RenjanaLog.e(TAG, "Failed to launch instance: ${e.message}")
                _error.value = e.message ?: "Failed to launch instance"
            }
        }
    }

    fun stopInstance(instanceId: String) {
        viewModelScope.launch {
            try {
                InstanceLifecycleService.stopInstance(
                    context = RenjanaApplication.get(),
                    instanceId = instanceId
                )
            } catch (e: Exception) {
                _error.value = "Failed to stop instance: ${e.message}"
            }
        }
    }

    fun clearError() {
        _error.value = null
    }
}
