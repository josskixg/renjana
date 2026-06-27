package com.fesu.renjana.core

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.fesu.renjana.RenjanaApplication
import com.fesu.renjana.hooks.PineHookManager
import com.fesu.renjana.models.Instance
import com.fesu.renjana.utils.RenjanaLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Carries all per-launch app data needed by [InstanceLauncher] internally.
 * Decouples the launch path from the single-app [Instance] model so that
 * any app in the instance's InstanceApp table can be launched independently.
 */
data class InstanceLaunchData(
    val instance: Instance,
    val packageName: String,
    val apkPath: String,
    val appName: String
)

/**
 * InstanceLauncher — Launches a container instance.
 *
 * Strategy (priority order):
 * 1. StubActivity path (WITH isolation) — DEFAULT
 *    Uses Pine hooks + VirtualClassLoader + StubActivity delegation.
 *    Provides storage isolation, GMS account virtualization, and hook-based
 *    interception. This is the primary launch path for virtualized instances.
 *
 * 2. getLaunchIntentForPackage() — FALLBACK (NO isolation)
 *    Direct Intent launch via system PackageManager. Only used when Pine is
 *    unavailable (e.g. on devices where Pine cannot initialize). The guest app
 *    runs natively with zero isolation — virtualization features are disabled.
 *
 * WrapperActivity (the old Strategy 2) is deprecated and no longer used.
 */
class InstanceLauncher(private val context: Context) {
    companion object {
        private const val TAG = "InstanceLauncher"
    }

    /**
     * Backward-compatible entry point. Picks the single app from the instance's
     * InstanceApp table and delegates to [launchApp].
     *
     * - 0 apps  → Failure (prompt user to add an app first)
     * - 1 app   → delegates to launchApp
     * - 2+ apps → Failure (caller should show an app-picker UI and call launchApp directly)
     */
    suspend fun launchInstance(instanceId: String): LaunchResult {
        val apps = withContext(Dispatchers.IO) {
            RenjanaApplication.get().database.instanceAppDao()
                .getAppsForInstanceOnce(instanceId)
        }
        return when {
            apps.isEmpty() -> {
                RenjanaLog.e(TAG, "No apps in instance $instanceId")
                LaunchResult.Failure("No apps added to this instance. Add an app first.")
            }
            apps.size == 1 -> launchApp(instanceId, apps.first().packageName)
            else -> {
                RenjanaLog.w(TAG, "Multiple apps in instance $instanceId — caller must use launchApp()")
                LaunchResult.Failure("Multiple apps in instance — use launchApp(instanceId, packageName)")
            }
        }
    }

    /**
     * Primary launch method for multi-app instances.
     * Resolves the [Instance] and the specific [InstanceAppEntity] for [packageName],
     * then drives the full stub-launch / direct-launch strategy.
     */
    suspend fun launchApp(instanceId: String, packageName: String): LaunchResult {
        return withContext(Dispatchers.Main) {
            try {
                val instanceManager = RenjanaApplication.get().instanceManager
                val instance = withContext(Dispatchers.IO) {
                    instanceManager.getInstanceById(instanceId)
                } ?: return@withContext LaunchResult.Failure("Instance not found: $instanceId")

                val appEntry = withContext(Dispatchers.IO) {
                    RenjanaApplication.get().database.instanceAppDao()
                        .getApp(instanceId, packageName)
                } ?: return@withContext LaunchResult.Failure(
                    "App $packageName not found in instance $instanceId"
                )

                if (appEntry.apkPath.isBlank()) {
                    RenjanaLog.e(TAG, "App $packageName has empty apkPath in instance $instanceId — cannot launch")
                    return@withContext LaunchResult.Failure("App APK path not found. Try removing and re-adding the app.")
                }

                val launchData = InstanceLaunchData(
                    instance = instance,
                    packageName = packageName,
                    apkPath = appEntry.apkPath,
                    appName = appEntry.appName
                )

                RenjanaLog.i(TAG, "Launching app $packageName in instance $instanceId")

                // Cache the instance so StubActivity can resolve dataPath/packageName
                // synchronously in attachBaseContext/onCreate without runBlocking on the main thread.
                ActivityStubManager.cacheInstance(instance)

                // Start foreground service so container stays alive when minimized
                InstanceLifecycleService.startForInstance(context, instanceId)

                // Strategy 1: StubActivity path (WITH isolation) — DEFAULT
                val launched = tryStubLaunch(launchData)

                if (launched) {
                    RenjanaLog.i(TAG, "StubActivity launch succeeded for ${launchData.packageName}")
                } else {
                    // Strategy 2: Fallback — direct intent launch (NO isolation)
                    RenjanaLog.w(TAG, "StubActivity launch unavailable, falling back to direct launch (no isolation)")
                    val fallbackLaunched = tryDirectLaunch(launchData.packageName)
                    if (fallbackLaunched) {
                        RenjanaLog.i(TAG, "Direct launch succeeded for ${launchData.packageName} (no isolation)")
                        CoroutineScope(Dispatchers.IO).launch {
                            try {
                                instanceManager.updateInstanceUsage(instanceId, System.currentTimeMillis(), true)
                            } catch (e: Exception) {
                                RenjanaLog.e(TAG, "Failed to update usage: ${e.message}")
                            }
                        }
                        return@withContext LaunchResult.FallbackNoIsolation("Pine unavailable — running without isolation")
                    } else {
                        RenjanaLog.e(TAG, "All launch strategies failed for ${launchData.packageName}")
                        return@withContext LaunchResult.Failure("All launch strategies failed for ${launchData.packageName}")
                    }
                }

                // Update lastUsed in background
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        instanceManager.updateInstanceUsage(
                            instanceId,
                            System.currentTimeMillis(),
                            true
                        )
                    } catch (e: Exception) {
                        RenjanaLog.e(TAG, "Failed to update usage: ${e.message}")
                    }
                }
                LaunchResult.Success
            } catch (e: Exception) {
                RenjanaLog.e(TAG, "launchApp failed: ${e.message}")
                LaunchResult.Failure(e.message ?: "Unknown error")
            }
        }
    }

    /**
     * StubActivity launch path — the DEFAULT strategy with full isolation.
     *
     * Steps:
     * 1. Verify Pine is available (required for hooks)
     * 2. Create an isolated VirtualClassLoader for the guest APK
     * 3. Install Pine guest hooks (package spoof, file redirect, GMS intercept, etc.)
     * 4. Assign Google account to instance if GMS is enabled
     * 5. Resolve the guest's launcher Activity class name
     * 6. Allocate a StubActivity slot via ActivityStubManager and build the launch Intent
     * 7. Start the stub Activity
     *
     * @return true if the stub was launched successfully, false on any failure
     */
    private suspend fun tryStubLaunch(launchData: InstanceLaunchData): Boolean {
        val instance = launchData.instance
        return try {
            // Pine is required for hook-based isolation
            if (!PineHookManager.isAvailable()) {
                RenjanaLog.w(TAG, "Pine not available, cannot use StubActivity path")
                return false
            }

            // Create isolated classloader for guest hooks
            val optimizedDir = File(instance.dataPath, "dex_opt")
            if (!optimizedDir.exists()) {
                optimizedDir.mkdirs()
            }
            val classLoader = VirtualClassLoader(
                apkPath = launchData.apkPath,
                instanceId = instance.id,
                optimizedDir = optimizedDir,
                parent = ClassLoader.getSystemClassLoader()
            )

            // Install Pine guest hooks (package spoof, file redirect, GMS intercept, etc.)
            val hooksInstalled = PineHookManager.installGuestHooks(
                launchData.packageName,
                classLoader,
                instance.id,
                instance.dataPath,
                launchData.apkPath,
                instance = instance
            )
            if (!hooksInstalled) {
                RenjanaLog.w(TAG, "Failed to install guest hooks for ${launchData.packageName}")
                return false
            }
            RenjanaLog.i(TAG, "Pine guest hooks installed for ${launchData.packageName}")

            // Assign Google account to instance if GMS virtualization is enabled
            if (instance.config.enableGms) {
                if (instance.accountId == null) {
                    RenjanaLog.w(TAG, "GMS enabled for instance ${instance.id} but no account assigned — guest will see device account")
                    // Do NOT block launch — continue without GMS assignment
                } else {
                    try {
                        val result = withContext(Dispatchers.IO) {
                            RenjanaApplication.get().googleSignInVirtualizer
                                .assignAccountToInstance(instance.id, instance.accountId)
                        }
                        if (result.isSuccess) {
                            RenjanaLog.i(TAG, "GMS account ${instance.accountId} assigned to instance ${instance.id}")
                        } else {
                            RenjanaLog.w(TAG, "Failed to assign GMS account: ${result.exceptionOrNull()?.message}")
                        }
                    } catch (e: Throwable) {
                        RenjanaLog.w(TAG, "GMS account assignment failed: ${e.message}")
                    }
                }
            }

            // Register deep link schemes from the guest APK manifest so that
            // incoming http/https/custom-scheme links can be routed to this instance.
            try {
                RenjanaApplication.get().intentRouter.filterManager
                    .registerSchemesFromApk(launchData.apkPath, instance.id)
            } catch (e: Throwable) {
                // Non-fatal: deep link routing will simply not work for this instance
                RenjanaLog.w(TAG, "Failed to register deep link schemes for ${launchData.packageName}: ${e.message}")
            }

            // Resolve the guest's launcher Activity class name
            val apkLoader = ApkLoader(context)
            // getLauncherActivity is a suspend function that already switches to Dispatchers.IO internally.
            val guestClassName = apkLoader.getLauncherActivity(launchData.apkPath)
            if (guestClassName.isNullOrEmpty()) {
                RenjanaLog.e(TAG, "No launcher activity found in ${launchData.apkPath}")
                return false
            }
            RenjanaLog.i(TAG, "Guest launcher activity: $guestClassName")

            // Allocate a StubActivity slot and build the launch Intent
            val stubIntent = ActivityStubManager.buildStubIntent(
                context = context,
                instanceId = instance.id,
                guestClass = guestClassName,
                guestIntent = null,
                apkPath = launchData.apkPath,
                launchMode = ActivityStubManager.LAUNCH_STANDARD
            )
            if (stubIntent == null) {
                RenjanaLog.e(TAG, "No free StubActivity slots available for ${launchData.packageName}")
                return false
            }

            stubIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(stubIntent)
            true
        } catch (e: Throwable) {
            RenjanaLog.e(TAG, "tryStubLaunch failed for ${launchData.packageName}: ${e.message}")
            false
        }
    }

    /**
     * Fallback: Direct launch via system PackageManager.
     * Uses getLaunchIntentForPackage() — same as tapping the app icon.
     * Provides NO isolation. Only used when Pine/StubActivity path is unavailable.
     * Returns false if app is not installed or has no launcher activity.
     */
    private fun tryDirectLaunch(packageName: String): Boolean {
        return try {
            val pm = context.packageManager
            val launchIntent = pm.getLaunchIntentForPackage(packageName)
                ?: return false

            launchIntent.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            }

            context.startActivity(launchIntent)
            true
        } catch (e: Throwable) {
            RenjanaLog.e(TAG, "tryDirectLaunch failed for $packageName: ${e.message}")
            false
        }
    }
}
