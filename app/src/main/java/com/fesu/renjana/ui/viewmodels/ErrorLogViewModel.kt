package com.fesu.renjana.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fesu.renjana.RenjanaApplication
import com.fesu.renjana.core.ErrorLogManager
import com.fesu.renjana.utils.RenjanaLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ErrorLogViewModel : ViewModel() {

    companion object {
        private const val TAG = "ErrorLogVM"
    }

    private val errorLogManager = ErrorLogManager(RenjanaApplication.get())

    private val _logs = MutableStateFlow<List<ErrorLogManager.CrashLog>>(emptyList())
    val logs: StateFlow<List<ErrorLogManager.CrashLog>> = _logs.asStateFlow()

    private val _selectedLogContent = MutableStateFlow<String?>(null)
    val selectedLogContent: StateFlow<String?> = _selectedLogContent.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        loadLogs()
    }

    fun loadLogs() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                _logs.value = withContext(Dispatchers.IO) {
                    errorLogManager.listLogs()
                }
            } catch (e: Exception) {
                RenjanaLog.e(TAG, "Failed to load logs", e)
                _message.value = "Failed to load logs: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun openLog(fileName: String) {
        viewModelScope.launch {
            try {
                _selectedLogContent.value = withContext(Dispatchers.IO) {
                    errorLogManager.readLog(fileName)
                }
            } catch (e: Exception) {
                _message.value = "Failed to read log: ${e.message}"
            }
        }
    }

    fun closeLog() {
        _selectedLogContent.value = null
    }

    fun deleteLog(fileName: String) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { errorLogManager.deleteLog(fileName) }
                _selectedLogContent.value = null
                loadLogs()
                _message.value = "Log deleted"
            } catch (e: Exception) {
                _message.value = "Failed to delete: ${e.message}"
            }
        }
    }

    fun clearAll() {
        viewModelScope.launch {
            try {
                val count = withContext(Dispatchers.IO) { errorLogManager.clearAll() }
                loadLogs()
                _message.value = "Cleared $count logs"
            } catch (e: Exception) {
                _message.value = "Failed to clear: ${e.message}"
            }
        }
    }

    fun captureLogcat() {
        viewModelScope.launch {
            try {
                val file = withContext(Dispatchers.IO) { errorLogManager.saveLogcatSnapshot() }
                if (file != null) {
                    _message.value = "Logcat snapshot saved"
                    loadLogs()
                } else {
                    _message.value = "Failed to capture logcat"
                }
            } catch (e: Exception) {
                _message.value = "Failed: ${e.message}"
            }
        }
    }

    fun clearMessage() { _message.value = null }
}
