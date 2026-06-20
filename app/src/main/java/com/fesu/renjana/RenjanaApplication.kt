package com.fesu.renjana

import android.app.Application
import com.fesu.renjana.core.AppManager
import com.fesu.renjana.core.DeviceRepository
import com.fesu.renjana.core.InstanceManager
import com.fesu.renjana.core.InstanceLauncher
import com.fesu.renjana.core.InstanceLifecycleService
import com.fesu.renjana.core.IntentRouter
import com.fesu.renjana.database.RenjanaDatabase
import com.fesu.renjana.gms.GoogleAccountManager
import com.fesu.renjana.utils.RenjanaLog

class RenjanaApplication : Application() {

    companion object {
        private const val TAG = "RenjanaApp"
        @Volatile
        private var instance: RenjanaApplication? = null
        fun get(): RenjanaApplication = instance ?: throw IllegalStateException("App not initialized")
    }

    val database: RenjanaDatabase by lazy { RenjanaDatabase.getInstance(this) }
    val googleAccountManager: GoogleAccountManager by lazy { GoogleAccountManager(database.googleAccountDao()) }
    val appManager: AppManager by lazy { AppManager(this) }
    val instanceManager: InstanceManager by lazy { InstanceManager(this, database.instanceDao()) }
    val instanceLauncher: InstanceLauncher by lazy { InstanceLauncher(this) }
    val intentRouter: IntentRouter by lazy { IntentRouter(this) }
    val deviceRepository: DeviceRepository by lazy { DeviceRepository(this) }

    /**
     * Reference to the running InstanceLifecycleService.
     * Set by the service when it binds, cleared when it destroys.
     */
    @Volatile
    var lifecycleService: InstanceLifecycleService? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        // Install crash handler FIRST — must catch any exception during init
        com.fesu.renjana.core.CrashHandler.install(this)
        RenjanaLog.i(TAG, "Renjana Application initialized")
    }
}
