package com.fesu.renjana.ui.viewmodels

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.fesu.renjana.RenjanaApplication
import com.fesu.renjana.models.Instance
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class RealDeviceInfo(
    val model: String,
    val brand: String,
    val manufacturer: String,
    val androidVersion: String,
    val sdkInt: Int,
    val fingerprint: String,
    val serial: String,
    val screenDpi: Int,
    val screenWidthPx: Int,
    val screenHeightPx: Int
)

class DiagnosticsViewModel(
    application: Application,
    val instanceId: String
) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "DiagnosticsVM"
    }

    private val instanceManager = RenjanaApplication.get().instanceManager

    private val _instance = MutableStateFlow<Instance?>(null)
    val instance: StateFlow<Instance?> = _instance.asStateFlow()

    private val _realDeviceInfo = MutableStateFlow<RealDeviceInfo?>(null)
    val realDeviceInfo: StateFlow<RealDeviceInfo?> = _realDeviceInfo.asStateFlow()

    init {
        viewModelScope.launch {
            _instance.value = instanceManager.getInstanceById(instanceId)
        }
        viewModelScope.launch {
            _realDeviceInfo.value = buildRealDeviceInfo(application)
        }
    }

    private fun buildRealDeviceInfo(application: Application): RealDeviceInfo {
        val serial = try {
            @Suppress("HardwareIds")
            Build.SERIAL.takeIf { it.isNotBlank() && it != Build.UNKNOWN } ?: "unknown"
        } catch (e: SecurityException) {
            "unknown"
        }
        val dm = application.resources.displayMetrics
        return RealDeviceInfo(
            model = Build.MODEL,
            brand = Build.BRAND,
            manufacturer = Build.MANUFACTURER,
            androidVersion = Build.VERSION.RELEASE,
            sdkInt = Build.VERSION.SDK_INT,
            fingerprint = try { Build.FINGERPRINT } catch (e: SecurityException) { "unknown" },
            serial = serial,
            screenDpi = dm.densityDpi,
            screenWidthPx = dm.widthPixels,
            screenHeightPx = dm.heightPixels
        )
    }
}

class DiagnosticsViewModelFactory(
    private val application: Application,
    private val instanceId: String
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
        return DiagnosticsViewModel(application, instanceId) as T
    }
}
