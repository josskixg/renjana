package com.fesu.renjana.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fesu.renjana.RenjanaApplication
import com.fesu.renjana.core.DeviceDatabase
import com.fesu.renjana.database.InstanceAppEntity
import com.fesu.renjana.models.Instance
import com.fesu.renjana.models.InstanceConfig
import com.fesu.renjana.utils.RenjanaLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

// ---------------------------------------------------------------------------
// Data class for randomized profile results
// ---------------------------------------------------------------------------

data class RandomizedProfile(
    val model: String,
    val brand: String,
    val manufacturer: String,
    val androidVersion: String,
    val androidId: String,
    val serial: String,
    val imei: String,
    val buildFingerprint: String
)

class InstanceDetailViewModel(
    private val instanceId: String
) : ViewModel() {
    companion object {
        private const val TAG = "InstanceDetailVM"
    }

    private val instanceManager = RenjanaApplication.get().instanceManager
    private val instanceLauncher = RenjanaApplication.get().instanceLauncher

    private val _instance = MutableStateFlow<Instance?>(null)
    val instance: StateFlow<Instance?> = _instance.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _actionSuccess = MutableStateFlow<String?>(null)
    val actionSuccess: StateFlow<String?> = _actionSuccess.asStateFlow()

    private val _isDeleted = MutableStateFlow(false)
    val isDeleted: StateFlow<Boolean> = _isDeleted.asStateFlow()

    // -----------------------------------------------------------------------
    // Instance apps (multi-app container)
    // -----------------------------------------------------------------------

    val instanceApps: StateFlow<List<InstanceAppEntity>> =
        instanceManager.getAppsForInstance(instanceId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun launchApp(packageName: String) {
        viewModelScope.launch {
            try {
                instanceLauncher.launchApp(instanceId, packageName)
            } catch (e: Exception) {
                RenjanaLog.e(TAG, "Failed to launch app $packageName: ${e.message}")
                _error.value = e.message ?: "Failed to launch app"
            }
        }
    }

    fun removeApp(packageName: String) {
        viewModelScope.launch {
            try {
                instanceManager.removeAppFromInstance(instanceId, packageName)
            } catch (e: Exception) {
                RenjanaLog.e(TAG, "Failed to remove app $packageName: ${e.message}")
                _error.value = e.message ?: "Failed to remove app"
            }
        }
    }

    // -----------------------------------------------------------------------
    // Tadiphone / device randomization state
    // -----------------------------------------------------------------------

    private val _isFetchingDevice = MutableStateFlow(false)
    val isFetchingDevice: StateFlow<Boolean> = _isFetchingDevice.asStateFlow()

    private val _randomizedProfile = MutableStateFlow<RandomizedProfile?>(null)
    val randomizedProfile: StateFlow<RandomizedProfile?> = _randomizedProfile.asStateFlow()

    init {
        loadInstance()
    }

    // -----------------------------------------------------------------------
    // Existing functions — unchanged
    // -----------------------------------------------------------------------

    private fun loadInstance() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val inst = instanceManager.getInstanceById(instanceId)
                _instance.value = inst
                if (inst == null) {
                    _error.value = "Instance not found"
                }
            } catch (e: Exception) {
                _error.value = e.message ?: "Failed to load instance"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun renameInstance(newName: String) {
        viewModelScope.launch {
            try {
                instanceManager.updateAppName(instanceId, newName)
                _instance.value = _instance.value?.copy(appName = newName)
                _actionSuccess.value = "Renamed to $newName"
            } catch (e: Exception) {
                _error.value = e.message ?: "Failed to rename"
            }
        }
    }

    fun updateConfig(config: InstanceConfig) {
        viewModelScope.launch {
            try {
                instanceManager.updateInstanceConfig(instanceId, config)
                _instance.value = _instance.value?.copy(config = config)
                _actionSuccess.value = "Configuration updated"
            } catch (e: Exception) {
                _error.value = e.message ?: "Failed to update config"
            }
        }
    }

    fun toggleGms(enabled: Boolean) {
        val current = _instance.value ?: return
        updateConfig(current.config.copy(enableGms = enabled))
    }

    fun toggleFingerprint(enabled: Boolean) {
        val current = _instance.value ?: return
        updateConfig(current.config.copy(enableFingerprint = enabled))
    }

    fun toggleSpoofSignature(enabled: Boolean) {
        val current = _instance.value ?: return
        updateConfig(current.config.copy(spoofSignature = enabled))
    }

    fun toggleAntiDetection(enabled: Boolean) {
        val current = _instance.value ?: return
        updateConfig(current.config.copy(enableAntiDetection = enabled))
    }

    fun updateDeviceSpoof(
        model: String?, brand: String?, manufacturer: String?,
        androidVersion: String?, androidId: String?, serial: String?
    ) {
        viewModelScope.launch {
            try {
                instanceManager.updateDeviceSpoof(
                    instanceId, model, brand, manufacturer, androidVersion, androidId, serial
                )
                _instance.value = _instance.value?.copy(
                    config = _instance.value!!.config.copy(
                        spoofModel = model?.ifBlank { null },
                        spoofBrand = brand?.ifBlank { null },
                        spoofManufacturer = manufacturer?.ifBlank { null },
                        spoofAndroidVersion = androidVersion?.ifBlank { null },
                        spoofAndroidId = androidId?.ifBlank { null },
                        spoofSerial = serial?.ifBlank { null },
                    )
                )
                _actionSuccess.value = "Device identity updated"
            } catch (e: Exception) {
                _error.value = e.message ?: "Failed to update device spoof"
            }
        }
    }

    fun clearData() {
        viewModelScope.launch {
            try {
                instanceManager.clearInstanceData(instanceId)
                _actionSuccess.value = "Data cleared"
            } catch (e: Exception) {
                _error.value = e.message ?: "Failed to clear data"
            }
        }
    }

    fun deleteInstance() {
        viewModelScope.launch {
            try {
                instanceManager.deleteInstance(instanceId)
                _isDeleted.value = true
            } catch (e: Exception) {
                _error.value = e.message ?: "Failed to delete instance"
            }
        }
    }

    fun launchInstance() {
        viewModelScope.launch {
            try {
                when (val result = instanceLauncher.launchInstance(instanceId)) {
                    is com.fesu.renjana.core.LaunchResult.Success -> { /* ok */ }
                    is com.fesu.renjana.core.LaunchResult.FallbackNoIsolation ->
                        _error.value = "⚠️ ${result.reason}"
                    is com.fesu.renjana.core.LaunchResult.Failure ->
                        _error.value = result.message
                }
            } catch (e: Exception) {
                RenjanaLog.e(TAG, "Failed to launch instance: ${e.message}")
                _error.value = e.message ?: "Failed to launch instance"
            }
        }
    }

    fun stopInstance() {
        viewModelScope.launch {
            try {
                com.fesu.renjana.core.InstanceLifecycleService.stopInstance(
                    context = RenjanaApplication.get(),
                    instanceId = instanceId
                )
            } catch (e: Exception) {
                RenjanaLog.e(TAG, "Failed to stop instance: ${e.message}")
                _error.value = e.message ?: "Failed to stop instance"
            }
        }
    }

    fun clearError() { _error.value = null }
    fun clearActionSuccess() { _actionSuccess.value = null }

    // -----------------------------------------------------------------------
    // Tadiphone device randomization
    // -----------------------------------------------------------------------

    /**
     * Fetches a random DeviceProfile via DeviceRepository (cache → network → local fallback),
     * generates IMEI / Android ID / serial, and emits the result via [randomizedProfile].
     */
    fun randomizeDeviceProfile() {
        viewModelScope.launch {
            _isFetchingDevice.value = true
            try {
                val repo = RenjanaApplication.get().deviceRepository
                val profile = repo.getRandomProfile()
                val seed = System.currentTimeMillis()
                val imei = DeviceDatabase.generateImei(profile, seed)
                val androidId = DeviceDatabase.generateAndroidId(seed)
                val serial = DeviceDatabase.generateSerial(profile.brand, seed)
                _randomizedProfile.value = RandomizedProfile(
                    model = profile.model,
                    brand = profile.brand,
                    manufacturer = profile.manufacturer,
                    androidVersion = profile.androidVersion,
                    androidId = androidId,
                    serial = serial,
                    imei = imei,
                    buildFingerprint = profile.buildFingerprint
                )
            } catch (e: Exception) {
                RenjanaLog.e(TAG, "randomizeDeviceProfile failed: ${e.message}")
                _error.value = "Failed to fetch device profile: ${e.message}"
            } finally {
                _isFetchingDevice.value = false
            }
        }
    }

    fun clearRandomizedProfile() {
        _randomizedProfile.value = null
    }

    fun updateExtendedFingerprint(config: InstanceConfig) {
        viewModelScope.launch {
            try {
                instanceManager.updateExtendedFingerprint(instanceId, config)
                _instance.value = _instance.value?.copy(config = config)
                _actionSuccess.value = "Extended fingerprint updated"
            } catch (e: Exception) {
                _error.value = e.message ?: "Failed to update fingerprint"
            }
        }
    }

    // -----------------------------------------------------------------------
    // Visual customization
    // -----------------------------------------------------------------------

    fun updateInstanceColor(color: String?) {
        val current = _instance.value ?: return
        val newConfig = current.config.copy(instanceColor = color)
        viewModelScope.launch {
            try {
                instanceManager.updateVisualConfig(instanceId, newConfig.instanceColor, newConfig.instanceEmoji)
                _instance.value = current.copy(config = newConfig)
                _actionSuccess.value = "Accent color updated"
            } catch (e: Exception) {
                _error.value = e.message ?: "Failed to update color"
            }
        }
    }

    fun updateInstanceEmoji(emoji: String?) {
        val current = _instance.value ?: return
        val newConfig = current.config.copy(instanceEmoji = emoji)
        viewModelScope.launch {
            try {
                instanceManager.updateVisualConfig(instanceId, newConfig.instanceColor, newConfig.instanceEmoji)
                _instance.value = current.copy(config = newConfig)
                _actionSuccess.value = "Label updated"
            } catch (e: Exception) {
                _error.value = e.message ?: "Failed to update emoji"
            }
        }
    }

    fun updateVisualConfig(color: String?, emoji: String?) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val current = _instance.value ?: return@launch
                val newConfig = current.config.copy(instanceColor = color, instanceEmoji = emoji?.ifBlank { null })
                instanceManager.updateVisualConfig(current.id, newConfig.instanceColor, newConfig.instanceEmoji)
                _instance.value = current.copy(config = newConfig)
            } catch (e: Exception) {
                _error.value = "Failed to save: ${e.message}"
            }
        }
    }
}
