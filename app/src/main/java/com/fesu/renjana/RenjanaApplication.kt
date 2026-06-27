package com.fesu.renjana

import android.app.Application
import com.fesu.renjana.core.ActivityStubManager
import com.fesu.renjana.core.AppManager
import com.fesu.renjana.core.DeviceRepository
import com.fesu.renjana.core.InstanceManager
import com.fesu.renjana.core.InstanceLauncher
import com.fesu.renjana.core.InstanceLifecycleService
import com.fesu.renjana.core.QuickSwitchBubbleService
import com.fesu.renjana.core.IntentRouter
import com.fesu.renjana.database.RenjanaDatabase
import com.fesu.renjana.gms.GoogleAccountManager
import com.fesu.renjana.utils.RenjanaLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

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
    val instanceManager: InstanceManager by lazy { InstanceManager(this, database.instanceDao(), database.instanceAppDao()) }
    val instanceLauncher: InstanceLauncher by lazy { InstanceLauncher(this) }
    val intentRouter: IntentRouter by lazy { IntentRouter(this) }
    val deviceRepository: DeviceRepository by lazy { DeviceRepository(this) }

    /** Application-scoped coroutine scope for background work that outlives any single ViewModel. */
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /**
     * Google Sign-In virtualizer for per-instance GMS account isolation.
     *
     * Constructed from the secure account store (Android Keystore-encrypted Room DAO)
     * and the token manager (OAuth 2.0 lifecycle). Lazily initialized on first access
     * to avoid overhead when GMS virtualization is not used.
     */
    val googleSignInVirtualizer: com.fesu.renjana.gms.GoogleSignInVirtualizer by lazy {
        val accountStore = com.fesu.renjana.gms.GoogleAccountStore(this, database.googleAccountDao())
        val tokenManager = com.fesu.renjana.gms.GoogleTokenManager(accountStore)
        com.fesu.renjana.gms.GoogleSignInVirtualizer(this, accountStore, tokenManager)
    }

    /**
     * Reference to the running InstanceLifecycleService.
     * Set by the service when it binds, cleared when it destroys.
     */
    @Volatile
    var lifecycleService: InstanceLifecycleService? = null

    /**
     * Reference to the running QuickSwitchBubbleService.
     * Set by the service in onCreate, cleared in onDestroy.
     */
    @Volatile
    var bubbleService: QuickSwitchBubbleService? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        // Install crash handler FIRST — must catch any exception during init
        com.fesu.renjana.core.CrashHandler.install(this)

        // Initialize Pine hook framework (non-root virtualization)
        val pineReady = com.fesu.renjana.hooks.PineHookManager.initialize()
        RenjanaLog.i(TAG, "Pine hook framework: ${if (pineReady) "READY" else "UNAVAILABLE (fallback mode)"}")

        // Initialize GuestInfoCache so GuestInfoCache.cache() works at instance launch.
        // Without this, cache() early-returns (appContext == null) and guest
        // PackageInfo/Resources are never populated.
        com.fesu.renjana.virtual.GuestInfoCache.initialize(this)
        RenjanaLog.i(TAG, "GuestInfoCache initialized")

        // Initialize the GoogleSignInVirtualizer singleton early so that any later
        // GoogleSignInVirtualizer.get() call (e.g. from hooks) finds it ready,
        // avoiding the singleton init race with the lazy property below.
        com.fesu.renjana.gms.GoogleSignInVirtualizer.init(this)

        RenjanaLog.i(TAG, "Renjana Application initialized")

        // Warm-up: re-populate in-memory state from Room DB after process restart.
        // Restores ActivityStubManager cache and GMS account map for all active instances.
        applicationScope.launch(Dispatchers.IO) {
            try {
                val activeInstances = instanceManager.getAllInstances().first()
                activeInstances.forEach { instance ->
                    // Re-populate ActivityStubManager cache so StubActivity can resolve
                    // dataPath/packageName without hitting the DB on the main thread.
                    ActivityStubManager.cacheInstance(instance)
                    // Re-populate GMS account map if instance has an assigned account.
                    if (instance.accountId != null && instance.config.enableGms) {
                        googleSignInVirtualizer.assignAccountToInstance(instance.id, instance.accountId)
                    }
                    // Pre-populate DeviceFingerprint in-memory map so hook intercepts
                    // don't hit a cold cache after process restart.
                    try {
                        com.fesu.renjana.hooks.DeviceFingerprint.getIdentifiers(instance.id)
                    } catch (e: Exception) {
                        RenjanaLog.w(TAG, "Could not warm-up fingerprint for ${instance.id}: ${e.message}")
                    }
                }
                RenjanaLog.i(TAG, "Warm-up: restored ${activeInstances.size} active instances")
            } catch (e: Throwable) {
                RenjanaLog.w(TAG, "Warm-up failed: ${e.message}")
            }
        }
    }
}
