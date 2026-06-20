package com.fesu.renjana.core

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.fesu.renjana.RenjanaApplication
import com.fesu.renjana.utils.RenjanaLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * InstanceLauncher — Launches a container instance.
 *
 * Strategy (priority order):
 * 1. getLaunchIntentForPackage() — direct Intent launch (most reliable)
 *    Works because the guest app is already installed on the device.
 *    Virtualization config (spoof, hooks) is best-effort for now (v0.1.0).
 * 2. WrapperActivity — reflection-based container (future full virtualization)
 *    Kept as fallback but currently fragile with AppCompatActivity/ReactNative.
 */
class InstanceLauncher(private val context: Context) {
    companion object {
        private const val TAG = "InstanceLauncher"
    }

    fun launchInstance(instanceId: String): Boolean {
        return try {
            RenjanaLog.i(TAG, "Launching instance: $instanceId")

            val instanceManager = RenjanaApplication.get().instanceManager
            val instance = kotlinx.coroutines.runBlocking {
                instanceManager.getInstanceById(instanceId)
            }

            if (instance == null) {
                RenjanaLog.e(TAG, "Instance not found: $instanceId")
                return false
            }

            // Start foreground service so container stays alive when minimized
            InstanceLifecycleService.startForInstance(context, instanceId)

            // Strategy 1: Direct intent launch (reliable — app is installed)
            val launched = tryDirectLaunch(instance.packageName)

            if (launched) {
                RenjanaLog.i(TAG, "Direct launch succeeded for ${instance.packageName}")
            } else {
                // Strategy 2: WrapperActivity fallback (for future virtualization)
                RenjanaLog.w(TAG, "Direct launch failed, falling back to WrapperActivity")
                val intent = Intent(context, WrapperActivity::class.java).apply {
                    putExtra(WrapperActivity.EXTRA_INSTANCE_ID, instanceId)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
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
            true
        } catch (e: Throwable) {
            RenjanaLog.e(TAG, "Failed to launch instance: ${e.message}")
            false
        }
    }

    /**
     * Direct launch via system PackageManager.
     * Uses getLaunchIntentForPackage() — same as tapping the app icon.
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
