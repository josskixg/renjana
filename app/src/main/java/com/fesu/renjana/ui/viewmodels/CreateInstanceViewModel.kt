package com.fesu.renjana.ui.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fesu.renjana.RenjanaApplication
import com.fesu.renjana.core.AppManager
import com.fesu.renjana.core.DeviceDatabase
import com.fesu.renjana.models.InstanceConfig
import com.fesu.renjana.utils.RenjanaLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class CreateInstanceViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        private const val TAG = "CreateInstanceViewModel"
    }

    private val instanceManager = RenjanaApplication.get().instanceManager

    private val _packageName = MutableStateFlow("")
    val packageName: StateFlow<String> = _packageName.asStateFlow()

    private val _apkPath = MutableStateFlow("")
    val apkPath: StateFlow<String> = _apkPath.asStateFlow()

    private val _appName = MutableStateFlow("")
    val appName: StateFlow<String> = _appName.asStateFlow()

    private val _isCreating = MutableStateFlow(false)
    val isCreating: StateFlow<Boolean> = _isCreating.asStateFlow()

    private val _creationSuccess = MutableStateFlow(false)
    val creationSuccess: StateFlow<Boolean> = _creationSuccess.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    // Config toggles
    private val _enableGms = MutableStateFlow(false)
    val enableGms: StateFlow<Boolean> = _enableGms.asStateFlow()

    private val _enableFingerprint = MutableStateFlow(true) // default true for better spoofing
    val enableFingerprint: StateFlow<Boolean> = _enableFingerprint.asStateFlow()

    private val _spoofSignature = MutableStateFlow(true)
    val spoofSignature: StateFlow<Boolean> = _spoofSignature.asStateFlow()

    private val _enableAntiDetection = MutableStateFlow(true)
    val enableAntiDetection: StateFlow<Boolean> = _enableAntiDetection.asStateFlow()

    // Device spoof (auto-randomized on prefill)
    private val _spoofModel = MutableStateFlow<String?>(null)
    val spoofModel: StateFlow<String?> = _spoofModel.asStateFlow()

    private val _spoofBrand = MutableStateFlow<String?>(null)
    val spoofBrand: StateFlow<String?> = _spoofBrand.asStateFlow()

    private val _spoofManufacturer = MutableStateFlow<String?>(null)
    val spoofManufacturer: StateFlow<String?> = _spoofManufacturer.asStateFlow()

    private val _spoofAndroidVersion = MutableStateFlow<String?>(null)
    val spoofAndroidVersion: StateFlow<String?> = _spoofAndroidVersion.asStateFlow()

    private val _spoofAndroidId = MutableStateFlow<String?>(null)
    val spoofAndroidId: StateFlow<String?> = _spoofAndroidId.asStateFlow()

    private val _spoofSerial = MutableStateFlow<String?>(null)
    val spoofSerial: StateFlow<String?> = _spoofSerial.asStateFlow()

    fun prefill(packageName: String, apkPath: String) {
        _packageName.value = packageName
        _apkPath.value = apkPath
        viewModelScope.launch {
            // Extract app name
            val appInfo = AppManager(getApplication()).extractAppInfo(apkPath)
            _appName.value = appInfo?.appName ?: packageName

            // Auto-randomize device identity via Tadiphone (cache-first, local fallback)
            try {
                val repo = RenjanaApplication.get().deviceRepository
                val seed = System.currentTimeMillis()
                val profile = repo.getRandomProfile()
                _spoofModel.value = profile.model
                _spoofBrand.value = profile.brand
                _spoofManufacturer.value = profile.manufacturer
                _spoofAndroidVersion.value = profile.androidVersion
                _spoofAndroidId.value = DeviceDatabase.generateAndroidId(seed)
                _spoofSerial.value = DeviceDatabase.generateSerial(profile.brand, seed)
                RenjanaLog.i(TAG, "Auto-randomized device: ${profile.brand} ${profile.model}")
            } catch (e: Exception) {
                RenjanaLog.w(TAG, "Device randomization failed (non-fatal): ${e.message}")
            }
        }
    }

    fun updateEnableGms(value: Boolean) { _enableGms.value = value }
    fun updateEnableFingerprint(value: Boolean) { _enableFingerprint.value = value }
    fun updateSpoofSignature(value: Boolean) { _spoofSignature.value = value }
    fun updateEnableAntiDetection(value: Boolean) { _enableAntiDetection.value = value }

    fun createInstance() {
        viewModelScope.launch {
            _isCreating.value = true
            try {
                val apkPath = _apkPath.value
                val packageName = _packageName.value

                val appInfo = AppManager(getApplication()).extractAppInfo(apkPath)
                val appName = appInfo?.appName ?: _appName.value.ifBlank { packageName }
                val versionName = appInfo?.versionName ?: "1.0"
                val versionCode = appInfo?.versionCode ?: 1

                RenjanaLog.i(TAG, "Creating instance for $packageName")

                val config = InstanceConfig(
                    enableGms = _enableGms.value,
                    enableFingerprint = _enableFingerprint.value,
                    spoofSignature = _spoofSignature.value,
                    enableAntiDetection = _enableAntiDetection.value,
                    spoofModel = _spoofModel.value,
                    spoofBrand = _spoofBrand.value,
                    spoofManufacturer = _spoofManufacturer.value,
                    spoofAndroidVersion = _spoofAndroidVersion.value,
                    spoofAndroidId = _spoofAndroidId.value,
                    spoofSerial = _spoofSerial.value
                )

                val result = instanceManager.createInstance(
                    packageName = packageName,
                    appName = appName,
                    versionName = versionName,
                    versionCode = versionCode,
                    apkPath = apkPath,
                    accountId = null,
                    config = config
                )

                if (result.isSuccess) {
                    _creationSuccess.value = true
                } else {
                    val exception = result.exceptionOrNull()
                    _error.value = exception?.message ?: "Failed to create instance"
                }
            } catch (e: Exception) {
                RenjanaLog.e(TAG, "Error creating instance: ${e.message}")
                _error.value = e.message ?: "Failed to create instance"
            } finally {
                _isCreating.value = false
            }
        }
    }

    fun clearError() { _error.value = null }
    fun resetSuccess() { _creationSuccess.value = false }
}
